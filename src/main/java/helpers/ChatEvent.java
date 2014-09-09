package main.java.helpers;
import java.nio.channels.SocketChannel;

import main.java.ChatServer;

public class ChatEvent {
    public ChatServer chatServer;
    public SocketChannel socketChannel;
    public byte[] data;

    public ChatEvent(ChatServer server, SocketChannel socket, byte[] data) {
	this.chatServer = server;
	this.socketChannel = socket;
	this.data = data;
    }
}