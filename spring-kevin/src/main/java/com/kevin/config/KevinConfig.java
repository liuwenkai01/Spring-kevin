package com.kevin.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@ComponentScan("com.kevin.service")
@EnableTransactionManagement
@EnableAsync
public class KevinConfig {

//	@Bean
//	public UserService userService(){
////		UserService userService = new UserService();
//		User a = user();
//		User b = user();
//		System.out.println(a == b);
//		return new UserService();
//	}
//
//	@Bean(autowireCandidate = false)
//	public User user1(){
//		return new User();
//	}
//
//	@Bean
//	public Order order(){
//		return new Order();
//	}

}
