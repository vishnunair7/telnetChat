package main.java.helpers;

public enum ChatOptions {
    ROOMS("/rooms", "Returns all the chat rooms"), 
    JOIN("/join ", "Type joined followed by group name to enter group"), // space is intentional	
    HELP("/help", "Shows the options available"),
    CREATE("/create ", "Create a group. Eg. \"/create newgroup\" will create a group with name newgroup"),
    LEAVE("/leave", "This option is used to leave a room. Is available only while you are inside a room"),
    MESSAGE("/message", "Used to send a private message to a particular user in a group. Enter /message <username> <message>"),
    MEMBERS("/members", "Shows the members in the group"),
    QUIT("/quit", "Quit from Chat");
    

    private String optionCommand;
    private String description;

    ChatOptions(String optionCommand, String description) {
	this.optionCommand = optionCommand;
	this.description = description;
    }

    public String getOptionCommand() {
	return optionCommand;
    }

    public String getDescription() {
	return description;
    }
}
