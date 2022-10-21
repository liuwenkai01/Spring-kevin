package com.kevin;


import com.kevin.config.KevinConfig;
import com.kevin.service.Order;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.util.ClassUtils;

public class TestApplication {

	public static void main(String[] args) {
		ApplicationContext applicationContext = new AnnotationConfigApplicationContext(KevinConfig.class);
		System.out.println(ClassUtils.getDefaultClassLoader());
		Order userService = (Order) applicationContext.getBean("order");
		System.out.println(userService);
//		OrderService orderService = (OrderService) applicationContext.getBean("factoryService");
//		System.out.println(orderService);

		System.out.println(applicationContext.getBean("a"));
	}

}
