package com.kevin.service;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.stereotype.Component;

@Component
public class FactoryService implements FactoryBean<OrderService> {

	@Override
	public OrderService getObject() throws Exception {
		return new OrderService();
	}

	@Override
	public Class<OrderService> getObjectType() {
		return OrderService.class;
	}
}
