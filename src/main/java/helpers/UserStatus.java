package main.java.helpers;

public enum UserStatus {
    /* 
     * CONNECTED - status where user has connected but logged in yet
     * LOGGED_IN - user has entered a user id and is logged in
     * IN_ROOM - user is in one of the rooms and chatting in that context
     */
	CONNECTED,
	LOGGED_IN,
	IN_ROOM
}
