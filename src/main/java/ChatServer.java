package main.java;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ChatServer implements Runnable {
    	private final static Logger LOGGER = Logger.getLogger(ChatServer.class.getName()); 
	// Selector will keep listening on multiple channels. This is monitored for events
	private Selector selector;
	
	// Channels that will connect to the selector
	private ServerSocketChannel serverSocketChannel;

	// Connection Details
	private InetAddress hostAddress;
	private int port;

	// 16KB buffer space. Channels always read from and write to buffers
	private ByteBuffer buffer = ByteBuffer.allocate(16384);

	//Handles all the events in a seperate thread
	private ChatHandler eventHandler;

	private Map<SocketChannel, List<Integer>> pendingChangesMap = new ConcurrentHashMap<SocketChannel, List<Integer>>();

	// Maps a SocketChannel to a list of ByteBuffer instances
	private Map<SocketChannel, List<ByteBuffer>> pendingData = new HashMap<SocketChannel, List<ByteBuffer>>();

	public ChatServer(int port, ChatHandler handler)
			throws IOException {
		this.port = port;
		this.selector = createAndInitializeSelector();
		this.eventHandler = handler;
	}

	private Selector createAndInitializeSelector() throws IOException {
	    
		Selector selector = SelectorProvider.provider().openSelector();
		this.serverSocketChannel = ServerSocketChannel.open();
		//this makes the channel non blocking
		serverSocketChannel.configureBlocking(false);

		InetSocketAddress inetSocketAddress = new InetSocketAddress(this.hostAddress,
				this.port);
		serverSocketChannel.socket().bind(inetSocketAddress);
		
		//Adding the channel to the selector and notify that the channel is ready to accept connections
		serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
		
		return selector;
	}
	
	public void run() {
		while (true) {
			try {
				// Process all changes that are requested by event handlers. 
				synchronized (this.pendingChangesMap) {
					processPendingRequests();
				}

				// Normal select operation. Looking for data in the input channels
				selector.select();
				
				//Get the selectionKey objects originating from each channel and prcess
				Iterator<SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();
				while (selectedKeys.hasNext()) {
					SelectionKey selectionKey = selectedKeys.next();
					selectedKeys.remove();
					manageSelectionKey(selectionKey);
				}
			} catch (Exception e) {
			    LOGGER.info("Exception occured while processing events from Channel" + e.getMessage());
			}
		}
	}

	private void manageSelectionKey(SelectionKey selectionKey)
		throws IOException {
	    if (!selectionKey.isValid()) {
	    	return;
	    }
	    if (selectionKey.isAcceptable()) {
	    	this.accept(selectionKey);
	    } else if (selectionKey.isReadable()) {
	    	this.read(selectionKey);
	    } else if (selectionKey.isWritable()) {
	    	this.write(selectionKey);
	    }
	}

	private void processPendingRequests() {
	    for (Entry<SocketChannel, List<Integer>> e : pendingChangesMap
	    		.entrySet()) {
	    	SocketChannel socket = e.getKey();
	    	for (Integer i : e.getValue()) {
	    		SelectionKey selectionKey = socket.keyFor(this.selector);
	    		if(selectionKey!=null) {
	    		    selectionKey.interestOps(i);
	    		}
	    	}
	    }
	    this.pendingChangesMap.clear();
	}

	private void accept(SelectionKey selectionKey) throws IOException {
		//Have to cast as selectionKey.channel is generic. In this case we know for sure its ServerSocketChannel
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey
				.channel();
		SocketChannel socketChannel = serverSocketChannel.accept();
		//nonBlocking
		socketChannel.configureBlocking(false);

		//Once connection is accepted, register channel with selector.
		//Request selector to notify when messages arrive at this channel(OP_READ)
		socketChannel.register(this.selector, SelectionKey.OP_READ);
		String welcomeMessage = "Welcome to the XYZ chat server";
		this.eventHandler.processData(this, socketChannel, welcomeMessage.getBytes(),
				welcomeMessage.getBytes().length);
	}

	private void read(SelectionKey selectionKey) throws IOException {
		SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
		
		//flush the buffer before new read.
		this.buffer.clear();
		int numRead;
		try {
			numRead = socketChannel.read(this.buffer);
		} catch (IOException e) {
			cancelKeyAndCloseChannel(selectionKey, socketChannel);
			return;
		}

		if (numRead == -1) {
		    	cancelKeyAndCloseChannel(selectionKey, socketChannel);
			return;
		}

		this.eventHandler.processData(this, socketChannel, this.buffer.array(),
				numRead);
	}

	private void write(SelectionKey selectionKey) throws IOException {
		SocketChannel socketChannel = (SocketChannel) selectionKey.channel();

		synchronized (this.pendingData) {
			List<ByteBuffer> queue =  this.pendingData.get(socketChannel);

			emptyBufferQueueAndWriteToChannel(socketChannel, queue);

			if (queue.isEmpty()) {
			    //Once all data is written. swtich mode back to read as we dont have the intent to write anymore
				selectionKey.interestOps(SelectionKey.OP_READ);
			}
		}
	}
	
	public void send(SocketChannel socketChannel, byte[] data) {
		//Setup channel for write by setting OP_WRITE
		if (!pendingChangesMap.containsKey(socketChannel)) {
			pendingChangesMap.put(socketChannel, new ArrayList<Integer>());
		}
		pendingChangesMap.get(socketChannel).add(SelectionKey.OP_WRITE);
		
		//Add to pending data the data that is to written to each socket channel
		synchronized (this.pendingData) {
			List<ByteBuffer> queue = this.pendingData.get(socketChannel);
			if (queue == null) {
				queue = new ArrayList<ByteBuffer>();
				this.pendingData.put(socketChannel, queue);
			}
			queue.add(ByteBuffer.wrap(data));
		}
		this.selector.wakeup();
	}

	private void emptyBufferQueueAndWriteToChannel(SocketChannel socketChannel,
		List<ByteBuffer> queue) throws IOException {
	    while (!queue.isEmpty()) {
	    	ByteBuffer buffer = queue.get(0);
	    	socketChannel.write(buffer);
	    	if (buffer.remaining() > 0) {
	    		break;
	    	}
	    	queue.remove(0);
	    }
	}
	
	private void cancelKeyAndCloseChannel(SelectionKey selectionKey,
		SocketChannel socketChannel) throws IOException {
	    selectionKey.cancel();
	    socketChannel.close();
	}

	public static void main(String[] args) {
		try {
			ChatHandler chatEventHandler = new ChatHandler();
			new Thread(chatEventHandler).start();
			new Thread(new ChatServer(9090, chatEventHandler)).start();
		} catch (IOException e) {
			LOGGER.severe("Exception during startup");
		}
	}
}