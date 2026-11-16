package com.bigdata.modules.telemetry.config.properties;

public class Auth {
    /** none / basic / bearer */
    private String type = "none";
    private String username = "";
    private String usernameRef = "";
    private String password = "";
    private String passwordRef = "";
    private String token = "";
    private String tokenRef = "";

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsernameRef() {
        return usernameRef;
    }

    public void setUsernameRef(String usernameRef) {
        this.usernameRef = usernameRef;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordRef() {
        return passwordRef;
    }

    public void setPasswordRef(String passwordRef) {
        this.passwordRef = passwordRef;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenRef() {
        return tokenRef;
    }

    public void setTokenRef(String tokenRef) {
        this.tokenRef = tokenRef;
    }
}
