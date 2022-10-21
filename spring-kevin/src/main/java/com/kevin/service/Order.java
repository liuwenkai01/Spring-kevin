package com.kevin.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Order {

	@Autowired
	private User user;

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

//	@Transactional
//	@Async
//	public void test(){
//		System.out.println("aop");
//	}
}
