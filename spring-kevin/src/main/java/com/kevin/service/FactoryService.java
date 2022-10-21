package com.kevin.service;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.stereotype.Component;

@Component
public class FactoryService implements FactoryBean<FactoryTest> {

	@Override
	public FactoryTest getObject() throws Exception {
		return new FactoryTest();
	}

	@Override
	public Class<FactoryTest> getObjectType() {
		return FactoryTest.class;
	}
}
