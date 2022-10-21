package com.kevin.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class UserService {

	@Autowired
	private OrderService orderService;

	@Async
	private void aa(){

	}

//	@Autowired
//	@Qualifier
//	private User user;
//
//
//	public User getUser() {
//		return user;
//	}
//
//	public void setUser(User user) {
//		this.user = user;
//	}
}
