package main.java;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import main.java.helpers.ChatEvent;
import main.java.helpers.ChatOptions;
import main.java.helpers.User;
import main.java.helpers.UserStatus;

public class ChatHandler implements Runnable {

    // Contains all events created that are to be handled by the chatserver
    private List<ChatEvent> queue = new LinkedList<ChatEvent>();

    // Contains a mappping between the channel the user is connected to and the
    // user and vice versa
    // This should ideally be a bidrectional map which is available in the
    // google commons library.
    private Map<SocketChannel, User> userMap = new ConcurrentHashMap<SocketChannel, User>();
    private Map<String, SocketChannel> userSocketSet = new ConcurrentHashMap<String, SocketChannel>();

    // Contains the mapping between chatRoomNames to members in it
    private Map<String, SortedSet<User>> chatRoomsMap = new ConcurrentHashMap<String, SortedSet<User>>();

    public void processData(ChatServer server, SocketChannel socket, byte[] data, int count) {
	if (chatRoomsMap.isEmpty()) {
	    chatRoomsMap.put("her", new TreeSet<User>());
	}
	// Handle new users
	if (!userMap.containsKey(socket)) {
	    handleNewUser(server, socket);
	    return;
	}

	byte[] dataCopy = new byte[count];
	System.arraycopy(data, 0, dataCopy, 0, count);
	String dataToBeProcessed = new String(dataCopy);
	// Replacing returns with empty text
	dataToBeProcessed = dataToBeProcessed.replace("\n", "").replace("\r", "");
	User user = userMap.get(socket);

	if (dataToBeProcessed.isEmpty()) {
	    return;
	}
	
	// taking actions according to current user statuses
	switch (user.getUserStatus()) {
	    case CONNECTED:
		handleConnectedUserButNotLoggedIn(server, socket, dataCopy, dataToBeProcessed, user);
		break;
		
	    case LOGGED_IN:
		if (dataToBeProcessed.startsWith(ChatOptions.ROOMS.getOptionCommand())) {
		    dataCopy = handleRoomsRequest();

		} else if (dataToBeProcessed.startsWith(ChatOptions.JOIN.getOptionCommand())) {
		    dataToBeProcessed = dataToBeProcessed.replaceFirst("/join", "").trim();
		    if (dataToBeProcessed.isEmpty()) {
			return;
		    }
		    dataCopy = handleJoinGroupsRequest(server, dataToBeProcessed, user);
		} else if (dataToBeProcessed.startsWith(ChatOptions.QUIT.getOptionCommand())) {
		    closeConectionWithClient(socket, user);
		    break;
		} else if (dataToBeProcessed.startsWith(ChatOptions.HELP.getOptionCommand())) {
		    dataCopy = showHelpOptions();
		} else if (dataToBeProcessed.startsWith(ChatOptions.CREATE.getOptionCommand())) {
		    dataCopy = createNewRoom(dataToBeProcessed);
		} else {
		    String errorMessage = "Invalid Option. Use /help to find options\n";
		    dataCopy = errorMessage.getBytes();
		}
		synchronized (queue) {
		    queue.add(new ChatEvent(server, socket, dataCopy));
		    queue.notify();
		}
		break;
		
	    case IN_ROOM:
		SortedSet<User> userSet = chatRoomsMap.get(user.getCurrentGroupName());
		if (dataToBeProcessed.startsWith("/leave")) {
		    removeUserFromGroupAndNotifyOthers(server, user, userSet);
		    break;
		}
		// This is the default action which is messaging in group
		for (User userInRoom : userSet) {
		    String message = user.getUserName() + ": " + dataToBeProcessed + "\n";
		    SocketChannel s = userSocketSet.get(userInRoom.getUserName());
		    synchronized (queue) {
			queue.add(new ChatEvent(server, s, message.getBytes()));
			queue.notify();
		    }
		}
	}
    }

    private byte[] createNewRoom(String dataToBeProcessed) {
	byte[] dataCopy;
	dataToBeProcessed = dataToBeProcessed.replaceFirst("/create", "").trim();
	chatRoomsMap.put(dataToBeProcessed, new TreeSet<User>());
	String message = "New room " + dataToBeProcessed + " created\nEnter /join " + dataToBeProcessed + " to join room\n";
	message += ("************\n");
	dataCopy = message.getBytes();
	return dataCopy;
    }

    private byte[] showHelpOptions() {
	byte[] dataCopy;
	StringBuffer message = new StringBuffer("Help - Use the below options: \n");
	for (ChatOptions chatOptions : ChatOptions.values()) {
	    message.append(chatOptions.getOptionCommand() + " - " + chatOptions.getDescription() + "\n");
	}
	message.append("************\n");
	dataCopy = message.toString().getBytes();
	return dataCopy;
    }

    // Removes user from the chat group and notifies all the others in the group
    private void removeUserFromGroupAndNotifyOthers(ChatServer server, User user, SortedSet<User> userSet) {
	userSet.remove(user);
	user.setUserStatus(UserStatus.LOGGED_IN);
	String message = "* user has left chat: " + user.getUserName() + "\n";
	for (User userInRoom : userSet) {
	    SocketChannel s = userSocketSet.get(userInRoom.getUserName());
	    synchronized (queue) {
		queue.add(new ChatEvent(server, s, message.getBytes()));
		queue.notify();
	    }
	}
    }

    // Closes the connection with user and removes him from user map
    private void closeConectionWithClient(SocketChannel socket, User user) {
	String bye = "BYE\n";
	synchronized (queue) {
	    try {
		socket.write(ByteBuffer.wrap(bye.getBytes()));
	    } catch (IOException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	    }
	}
	userMap.remove(socket);
	userSocketSet.remove(user.getUserName());
	try {
	    socket.close();
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    // Adds a user to a group and notifies others in the group
    private byte[] handleJoinGroupsRequest(ChatServer server, String dataToBeProcessed, User user) {
	byte[] dataCopy;
	SortedSet<User> userSet = chatRoomsMap.get(dataToBeProcessed);
	String message = "* new user joined " + dataToBeProcessed + ": " + user.getUserName() + "\n";
	for (User userInRoom : userSet) {
	    SocketChannel s = userSocketSet.get(userInRoom.getUserName());
	    synchronized (queue) {
		queue.add(new ChatEvent(server, s, message.getBytes()));
	    }
	}
	userSet.add(user);
	user.setCurrentGroupName(dataToBeProcessed);
	user.setUserStatus(UserStatus.IN_ROOM);
	chatRoomsMap.put(dataToBeProcessed, userSet);

	// Using StringBuffer due to multiple edits and also string buffer is
	// thread safe
	StringBuffer response = new StringBuffer("Entering room: " + dataToBeProcessed + "\n");
	for (User userInRoom : userSet) {
	    response.append(" * " + userInRoom.getUserName() + " ");
	    if (userInRoom.getUserName().equals(user.getUserName())) {
		response.append("(** this is you)");
	    }
	    response.append("\n");
	}
	response.append("end of list.\n");
	dataCopy = response.toString().getBytes();
	return dataCopy;
    }

    // Returns all the rooms
    private byte[] handleRoomsRequest() {
	byte[] dataCopy;
	StringBuffer response = new StringBuffer("Active rooms are:\n");
	for (Entry<String, SortedSet<User>> e : chatRoomsMap.entrySet()) {
	    response.append(" * " + e.getKey() + " (" + e.getValue().size() + ")\n");
	}
	response.append("end of list.\n");
	dataCopy = response.toString().getBytes();
	return dataCopy;
    }

    // This function handles creation of usernames
    private void handleConnectedUserButNotLoggedIn(ChatServer server, SocketChannel socket, byte[] dataCopy,
	    String dataToBeProcessed, User user) {
	if (user.getUserStatus() == UserStatus.CONNECTED && !dataToBeProcessed.trim().isEmpty()) {
	    if (userSocketSet.containsKey(dataToBeProcessed)) {
		String errorMsg = "Sorry, name taken. from \nLogin Name?\n" + socket.hashCode();
		dataCopy = errorMsg.getBytes();
	    } else {
		user.setUserName(dataToBeProcessed);
		user.setUserStatus(UserStatus.LOGGED_IN);
		String successMessage = "Welcome " + dataToBeProcessed + "!\n";
		dataCopy = successMessage.getBytes();
		userSocketSet.put(dataToBeProcessed, socket);
		userMap.put(socket, user);
	    }
	}
	synchronized (queue) {
	    queue.add(new ChatEvent(server, socket, dataCopy));
	    queue.notify();
	}
    }

    private void handleNewUser(ChatServer server, SocketChannel socket) {
	byte[] dataCopy;
	String loginMessage = "\nLogin?\n";
	dataCopy = loginMessage.getBytes();
	userMap.put(socket, new User("", UserStatus.CONNECTED));
	synchronized (queue) {
	    queue.add(new ChatEvent(server, socket, dataCopy));
	    queue.notify();
	}
    }

    public void run() {
	ChatEvent dataEvent;
	// Continuosly polling data in queue
	while (true) {
	    synchronized (queue) {
		while (queue.isEmpty()) {
		    try {
			queue.wait();
		    } catch (InterruptedException e) {
		    }
		}
		dataEvent = queue.remove(0);
	    }
	    dataEvent.chatServer.send(dataEvent.socketChannel, dataEvent.data);
	}
    }
}