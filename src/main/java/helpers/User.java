package main.java.helpers;

public class User implements Comparable {
    private String userName;
    private UserStatus userStatus;
    private String currentRoomName;

    public User(String userName, UserStatus userStatus) {
	super();
	this.userName = userName;
	this.userStatus = userStatus;
    }

    public int compareTo(Object o) {
	int result = 1;
	if (o == null) {
	    result = 0;
	} else {
	    User user = (User) o;
	    result = this.userName.compareTo(user.userName);
	}
	return result;
    }
    
    public String getUserName() {
        return userName;
    }

    public UserStatus getUserStatus() {
        return userStatus;
    }

    public String getCurrentRoomName() {
        return currentRoomName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public void setUserStatus(UserStatus userStatus) {
        this.userStatus = userStatus;
    }

    public void setCurrentRoomName(String currentGroupName) {
        this.currentRoomName = currentGroupName;
    }
}
