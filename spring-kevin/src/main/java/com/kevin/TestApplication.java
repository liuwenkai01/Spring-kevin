package com.kevin;


import com.kevin.config.KevinConfig;
import com.kevin.service.Order;
import com.kevin.service.OrderService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class TestApplication {

	public static void main(String[] args) {
		ApplicationContext applicationContext = new AnnotationConfigApplicationContext(KevinConfig.class);

		Order userService = (Order) applicationContext.getBean("order");
		System.out.println(userService);

		OrderService orderService = (OrderService) applicationContext.getBean("factoryService");
		System.out.println(orderService);
	}

}
