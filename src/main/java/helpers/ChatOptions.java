package main.java.helpers;

public enum ChatOptions {
    ROOMS("/rooms", "Returns all the chat rooms"), 
    JOIN("/join ", "Type joined followed by group name to enter group"), // space is intentional													// intentional
    HELP("/help", "Shows the options available"), 
    QUIT("/quit", "Quit from Chat"), CREATE("/create ",
	    "Create a group. Eg. \"/create newgroup\" will create a group with name newgroup");

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
