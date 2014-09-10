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

    private static final String ASTERISK_SEPERATORS = "************";

    private static final String NEW_LINE = "\n";

    // Contains all events created that are to be handled by the chatserver
    private List<ChatEvent> queue = new LinkedList<ChatEvent>();

    // Contains a mappping between the channel the user is connected to and the
    // user and vice versa
    // This should ideally be a bidrectional map which is available in the
    // google commons library.
    private Map<SocketChannel, User> socketUserMap = new ConcurrentHashMap<SocketChannel, User>();
    private Map<String, SocketChannel> userSocketMap = new ConcurrentHashMap<String, SocketChannel>();

    // Contains the mapping between chatRoomNames to members in it
    private Map<String, SortedSet<User>> chatRoomsMap = new ConcurrentHashMap<String, SortedSet<User>>();

    public void processData(ChatServer server, SocketChannel socket, byte[] data, int count) {
	// Handle new users
	if (!socketUserMap.containsKey(socket)) {
	    handleNewUser(server, socket);
	    return;
	}

	byte[] dataToBeProcessedInBytes = new byte[count];
	System.arraycopy(data, 0, dataToBeProcessedInBytes, 0, count);
	String dataToBeProcessed = new String(dataToBeProcessedInBytes);

	// Replacing returns with empty text
	dataToBeProcessed = dataToBeProcessed.replace(NEW_LINE, "").replace("\r", "");
	User user = socketUserMap.get(socket);

	if (dataToBeProcessed.isEmpty()) {
	    return;
	}

	// taking actions according to current user statuses
	switch (user.getUserStatus()) {
	case CONNECTED:
	    handleConnectedUserButNotLoggedIn(server, socket, dataToBeProcessedInBytes,
		    dataToBeProcessed, user);
	    break;

	case LOGGED_IN:
	    if (dataToBeProcessed.startsWith(ChatOptions.ROOMS.getOptionCommand())) {
		dataToBeProcessedInBytes = handleRoomsRequest();
	    } else if (dataToBeProcessed.startsWith(ChatOptions.JOIN.getOptionCommand())) {
		dataToBeProcessed = dataToBeProcessed.replaceFirst("/join", "").trim();
		if (dataToBeProcessed.isEmpty()) {
		    return;
		}
		dataToBeProcessedInBytes = handleJoinRoomsRequest(server, dataToBeProcessed, user);
	    } else if (dataToBeProcessed.startsWith(ChatOptions.QUIT.getOptionCommand())) {
		closeConectionWithClient(socket, user);
		break;
	    } else if (dataToBeProcessed.startsWith(ChatOptions.HELP.getOptionCommand())) {
		dataToBeProcessedInBytes = showHelpOptions();
	    } else if (dataToBeProcessed.startsWith(ChatOptions.CREATE.getOptionCommand())) {
		dataToBeProcessedInBytes = createNewRoom(dataToBeProcessed);
	    } else {
		String errorMessage = "Invalid Option. Use /help to find options" + NEW_LINE;
		dataToBeProcessedInBytes = errorMessage.getBytes();
	    }

	    synchronized (queue) {
		queue.add(new ChatEvent(server, socket, dataToBeProcessedInBytes));
		queue.notify();
	    }
	    break;

	case IN_ROOM:
	    SortedSet<User> userSet = chatRoomsMap.get(user.getCurrentRoomName());
	    if (dataToBeProcessed.startsWith(ChatOptions.LEAVE.getOptionCommand())) {
		removeUserFromRoomAndNotifyOthers(server, user, userSet);
		break;
	    } else if (dataToBeProcessed.startsWith(ChatOptions.MEMBERS.getOptionCommand())) {
		synchronized (queue) {
		    queue.add(new ChatEvent(server, socket, showMembers(user).getBytes()));
		    queue.notify();
		}
		break;
	    } else if (dataToBeProcessed.startsWith(ChatOptions.MESSAGE.getOptionCommand())) {
		dataToBeProcessed = dataToBeProcessed.replaceFirst("/message", "").trim();
		String toUserName = dataToBeProcessed.substring(0, dataToBeProcessed.indexOf(" "));
		String message = dataToBeProcessed.substring(dataToBeProcessed.indexOf(" "));
		if (toUserName == null || message == null) {
		    message = "Invalid Syntax. Try again \"/message <username> <message>\""
			    + NEW_LINE;
		    synchronized (queue) {
			queue.add(new ChatEvent(server, socket, message.getBytes()));
			queue.notify();
		    }
		    return;
		}
		sendMessage(server, user, toUserName, message, socket);
		break;
	    } else if (dataToBeProcessed.startsWith(ChatOptions.ROOMS.getOptionCommand())) {
		dataToBeProcessedInBytes = handleRoomsRequest();
		synchronized (queue) {
		    queue.add(new ChatEvent(server, socket, dataToBeProcessedInBytes));
		    queue.notify();
		}
	    }

	    // This is the default action which is messaging in room
	    for (User userInRoom : userSet) {
		String message = user.getUserName() + ": " + dataToBeProcessed + NEW_LINE;
		SocketChannel s = userSocketMap.get(userInRoom.getUserName());
		synchronized (queue) {
		    queue.add(new ChatEvent(server, s, message.getBytes()));
		    queue.notify();
		}
	    }
	}
    }

    private void sendMessage(ChatServer server, User user, String toUserName, String message,
	    SocketChannel fromSocketChannel) {
	message = "Private Message from " + user.getUserName() + ": " + message + NEW_LINE;
	SocketChannel toSocketChannel = userSocketMap.get(toUserName.trim().toLowerCase());
	User toUserObject = socketUserMap.get(toSocketChannel);

	if (toSocketChannel == null
		|| !user.getCurrentRoomName().equals(toUserObject.getCurrentRoomName())) {
	    message = "User with username " + toUserName + " not present in room" + NEW_LINE;
	    toSocketChannel = fromSocketChannel;
	}

	synchronized (queue) {
	    queue.add(new ChatEvent(server, toSocketChannel, message.getBytes()));
	    queue.notify();
	}
    }

    private String showMembers(User user) {
	SortedSet<User> userSet = chatRoomsMap.get(user.getCurrentRoomName());
	StringBuffer message = new StringBuffer("Members:" + NEW_LINE);
	for (User userInRoom : userSet) {
	    message.append(" * " + userInRoom.getUserName() + " ");
	    if (userInRoom.getUserName().equals(user.getUserName())) {
		message.append("(** this is you)");
	    }
	    message.append(NEW_LINE);
	}
	return message.toString();
    }

    private byte[] createNewRoom(String dataToBeProcessed) {
	byte[] dataCopy;
	dataToBeProcessed = dataToBeProcessed.replaceFirst("/create", "").trim();
	if (chatRoomsMap.containsKey(dataToBeProcessed)) {
	    String message = "Room with name already exists. Try again" + NEW_LINE;
	    message += (ASTERISK_SEPERATORS + NEW_LINE);
	    dataCopy = message.getBytes();
	    return dataCopy;
	}
	chatRoomsMap.put(dataToBeProcessed, new TreeSet<User>());
	String message = "New room " + dataToBeProcessed + " created\nEnter /join "
		+ dataToBeProcessed + " to join room" + NEW_LINE;
	message += (ASTERISK_SEPERATORS + NEW_LINE);
	dataCopy = message.getBytes();
	return dataCopy;
    }

    private byte[] showHelpOptions() {
	byte[] dataCopy;
	StringBuffer message = new StringBuffer("Help - Use the below options: " + NEW_LINE);
	for (ChatOptions chatOptions : ChatOptions.values()) {
	    message.append(chatOptions.getOptionCommand() + " - " + chatOptions.getDescription()
		    + NEW_LINE);
	}
	message.append(ASTERISK_SEPERATORS + NEW_LINE);
	dataCopy = message.toString().getBytes();
	return dataCopy;
    }

    // Removes user from the chat room and notifies all the others in the room
    private void removeUserFromRoomAndNotifyOthers(ChatServer server, User user,
	    SortedSet<User> userSet) {
	user.setUserStatus(UserStatus.LOGGED_IN);
	String message = "* user has left chat: " + user.getUserName();
	for (User userInRoom : userSet) {
	    SocketChannel s = userSocketMap.get(userInRoom.getUserName());
	    if (userInRoom.getUserName().equals(user.getUserName())) {
		String modMessage = message + " (** this is you)";
		synchronized (queue) {
		    queue.add(new ChatEvent(server, s, (modMessage + NEW_LINE).getBytes()));
		    queue.notify();
		}
		continue;
	    }
	    synchronized (queue) {
		queue.add(new ChatEvent(server, s, (message + NEW_LINE).getBytes()));
		queue.notify();
	    }
	}
	userSet.remove(user);
	user.setCurrentRoomName("");
    }

    // Closes the connection with user and removes him from user map
    private void closeConectionWithClient(SocketChannel socket, User user) {
	String bye = "BYE" + NEW_LINE;
	synchronized (queue) {
	    try {
		socket.write(ByteBuffer.wrap(bye.getBytes()));
	    } catch (IOException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	    }
	}
	socketUserMap.remove(socket);
	userSocketMap.remove(user.getUserName());
	try {
	    socket.close();
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    // Adds a user to a room and notifies others in the room
    private byte[] handleJoinRoomsRequest(ChatServer server, String dataToBeProcessed, User user) {
	byte[] responseInBytes;
	SortedSet<User> userSet = chatRoomsMap.get(dataToBeProcessed);
	if (userSet == null) {
	    String message = "Room not Found. /create <roomname> to create" + NEW_LINE;
	    responseInBytes = message.getBytes();
	    return responseInBytes;
	}
	String message = "* new user joined " + dataToBeProcessed + ": " + user.getUserName()
		+ NEW_LINE;
	for (User userInRoom : userSet) {
	    SocketChannel s = userSocketMap.get(userInRoom.getUserName());
	    synchronized (queue) {
		queue.add(new ChatEvent(server, s, message.getBytes()));
	    }
	}
	userSet.add(user);
	user.setCurrentRoomName(dataToBeProcessed);
	user.setUserStatus(UserStatus.IN_ROOM);
	chatRoomsMap.put(dataToBeProcessed, userSet);

	// Using StringBuffer due to multiple edits and also string buffer is
	// thread safe
	StringBuffer response = new StringBuffer("Entering room: " + dataToBeProcessed + NEW_LINE);
	for (User userInRoom : userSet) {
	    response.append(" * " + userInRoom.getUserName() + " ");
	    if (userInRoom.getUserName().equals(user.getUserName())) {
		response.append("(** this is you)");
	    }
	    response.append(NEW_LINE);
	}
	response.append("end of list." + NEW_LINE);
	responseInBytes = response.toString().getBytes();
	return responseInBytes;
    }

    // Returns all the rooms
    private byte[] handleRoomsRequest() {
	byte[] dataCopy;
	if (chatRoomsMap.isEmpty()) {
	    String createResponse = "No rooms. Create using \"/create <roomname>\"" + NEW_LINE
		    + ASTERISK_SEPERATORS + NEW_LINE;
	    return createResponse.getBytes();
	}
	StringBuffer response = new StringBuffer("Active rooms are:" + NEW_LINE);
	for (Entry<String, SortedSet<User>> e : chatRoomsMap.entrySet()) {
	    response.append(" * " + e.getKey() + " (" + e.getValue().size() + ")" + NEW_LINE);
	}
	response.append("end of list." + NEW_LINE);
	dataCopy = response.toString().getBytes();
	return dataCopy;
    }

    // This function handles creation of usernames
    private void handleConnectedUserButNotLoggedIn(ChatServer server, SocketChannel socket,
	    byte[] dataCopy, String dataToBeProcessed, User user) {
	if (user.getUserStatus() == UserStatus.CONNECTED && !dataToBeProcessed.trim().isEmpty()) {
	    if (userSocketMap.containsKey(dataToBeProcessed.toLowerCase())) {
		String errorMsg = "Sorry, name taken. from \nLogin Name?" + NEW_LINE;
		dataCopy = errorMsg.getBytes();
	    } else {
		user.setUserName(dataToBeProcessed.toLowerCase());
		user.setUserStatus(UserStatus.LOGGED_IN);
		String successMessage = "Welcome " + dataToBeProcessed + "!" + NEW_LINE;
		dataCopy = successMessage.getBytes();
		userSocketMap.put(dataToBeProcessed.toLowerCase(), socket);
		socketUserMap.put(socket, user);
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
	socketUserMap.put(socket, new User("", UserStatus.CONNECTED));
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