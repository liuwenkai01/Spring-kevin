package com.kevin.config;

import com.kevin.service.Order;
import com.kevin.service.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("com.kevin")
public class KevinConfig1 {

//	@Bean
//	public UserService userService(){
////		UserService userService = new UserService();
//		User a = user();
//		User b = user();
//		System.out.println(a == b);
//		return new UserService();
//	}
//
	@Bean
	public User user(){
		return new User();
	}

	@Bean
	public Order order(){
		return new Order();
	}

}
