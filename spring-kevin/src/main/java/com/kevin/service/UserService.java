package com.kevin.service;

import org.springframework.stereotype.Component;

@Component
public class UserService {

	private User user;


	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}
}
