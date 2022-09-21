/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.support;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.*;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;

import java.util.*;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}

	/**
	 * BeanFactoryPostProcessors按入场方式分为：
	 * 1. 程序员调用ApplicationContext的API手动添加
	 * 2. Spring自己扫描出来的
	 *
	 * BeanFactoryPostProcessor按类型又可以分为：
	 * 1. 普通BeanFactoryPostProcessor
	 * 2. BeanDefinitionRegistryPostProcessor
	 *
	 * 执行顺序顺序如下：
	 * 1. 执行手动添加的BeanDefinitionRegistryPostProcessor                       的postProcessBeanDefinitionRegistry()方法
	 * 2. 执行扫描出来的BeanDefinitionRegistryPostProcessor（实现了PriorityOrdered）的postProcessBeanDefinitionRegistry()方法  PS：此时就这ConfigurationClassPostProcessor一个后置处理器
	 * 3. 执行扫描出来的BeanDefinitionRegistryPostProcessor（实现了Ordered）		   的postProcessBeanDefinitionRegistry()方法
	 * 4. 执行扫描出来的BeanDefinitionRegistryPostProcessor（普通）				   的postProcessBeanDefinitionRegistry()方法
	 * 5. 执行扫描出来的BeanDefinitionRegistryPostProcessor（所有）				   的postProcessBeanFactory()方法
	 *
	 * 6. 执行手动添加的BeanFactoryPostProcessor								   的postProcessBeanFactory()方法
	 * 7. 执行扫描出来的BeanFactoryPostProcessor（实现了PriorityOrdered）		   的postProcessBeanFactory()方法
	 * 8. 执行扫描出来的BeanFactoryPostProcessor（实现了Ordered）		   		   的postProcessBeanFactory()方法
	 * 9. 执行扫描出来的BeanFactoryPostProcessor（普通）				   		   的postProcessBeanFactory()方法
	 *
	 * ConfigurationClassPostProcessor就会在第2步执行，会进行扫描
	 */
	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		/** ========第一步Begin：调用BeanDefinitionRegistryPostProcessor的后置处理器 Begin ==========*/
		/**
		 * 定义存放已经调用处理过的后置处理器集合
		 */
		Set<String> processedBeans = new HashSet<>();
		/**
		 * 判断我们的beanFactory是否实现了BeanDefinitionRegistry(实现了该结构就有注册和获取Bean定义的能力）
		 * beanFactory是DefaultListableBeanFactory，是BeanDefinitionRegistry的实现类，所以肯定满足if
		 * 并且强行把我们的bean工厂转为BeanDefinitionRegistry，因为待会需要注册Bean定义
		 * BeanDefinitionRegistry registry = beanFactory
		 */
		if (beanFactory instanceof BeanDefinitionRegistry) {

			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

			/**
			 * 定义保存实现了BeanFactoryPostProcessor类型的后置处理器集合
			 */
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();

			/**
			 * 定义保存实现了BeanDefinitionRegistryPostProcessor类型的后置处理器集合
			 * BeanDefinitionRegistryPostProcessor扩展了BeanFactoryPostProcessor
			 */
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();


			//----------------第①步：循环我们传递进来的beanFactoryPostProcessors开始-- 优先级No1- begin-------------*/
			/**
			 * 循环传进来的beanFactoryPostProcessors，正常情况下，beanFactoryPostProcessors肯定没有数据
			 * 因为beanFactoryPostProcessors是获得手动添加的，而不是spring扫描的
			 * 只有手动调用annotationConfigApplicationContext.addBeanFactoryPostProcessor(XXX)才会有数据
			 */
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {

				/**
				 * 判断postProcessor是不是BeanDefinitionRegistryPostProcessor，因为BeanDefinitionRegistryPostProcessor
				 * 扩展了BeanFactoryPostProcessor，所以这里先要判断是不是BeanDefinitionRegistryPostProcessor
				 * 是的话，直接执行postProcessBeanDefinitionRegistry方法，然后把对象装到registryProcessors里面去
				 *
				 * 如果类型匹配则进行类型转换  BeanDefinitionRegistryPostProcessor registryProcessor = postProcessor
				 */
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					BeanDefinitionRegistryPostProcessor registryProcessor = (BeanDefinitionRegistryPostProcessor) postProcessor;
					//调用它作为BeanDefinitionRegistryPostProcessor的处理器的后置方法
					registryProcessor.postProcessBeanDefinitionRegistry(registry);

					//添加到我们用于保存的BeanDefinitionRegistryPostProcessor的集合中
					registryProcessors.add(registryProcessor);
				}
				else {
					//若没有实现BeanDefinitionRegistryPostProcessor 接口，那么他就是BeanFactoryPostProcessor
					//把当前的后置处理器加入到regularPostProcessors中
					regularPostProcessors.add(postProcessor);
				}
			}
			//----------------第①步：循环我们传递进来的beanFactoryPostProcessors结束-- 优先级No1- end----------------*/


			//定义一个集合用于保存临时准备创建的BeanDefinitionRegistryPostProcessor
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();


			//----------------第②步：调用内置实现PriorityOrdered接口BeanDefinitionRegistryPostProcessor开始--优先级No2-Begin--------------/
			/**
			 * 去容器中获取BeanDefinitionRegistryPostProcessor的bean的处理器名称
			 * 获取BeanDefinition注册后置处理器
			 * 获得实现BeanDefinitionRegistryPostProcessor接口的类的BeanName:org.springframework.context.annotation.internalConfigurationAnnotationProcessor
			 * 并且装入数组postProcessorNames，我理解一般情况下，只会找到一个(ConfigurationClassPostProcessor.class)
			 * 这里需要注意，为什么我自己创建了一个实现BeanDefinitionRegistryPostProcessor接口的类，也打上了@Component注解
			 * 配置类也加上了@Component注解，但是这里却没有拿到
			 * 因为直到这一步，Spring还没有去扫描，扫描是在ConfigurationClassPostProcessor类中完成的，也就是下面的第一个
			 * invokeBeanDefinitionRegistryPostProcessors方法
			 */
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			//循环筛选出来的匹配BeanDefinitionRegistryPostProcessor的类型名称
			for (String ppName : postProcessorNames) {
				//判断是否实现了PriorityOrdered接口（ConfigurationClassPostProcessor实现了PriorityOrdered接口）
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					/**
					 * 显示的调用getBean()的方式获得ConfigurationClassPostProcessor类，并且放到currentRegistryProcessors
					 */
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					//把name放到processedBeans，后续会根据这个集合来判断处理器是否已经被执行过了
					processedBeans.add(ppName);
				}
			}

			/**
			 * 对currentRegistryProcessors集合中BeanDefinitionRegistryPostProcessor进行排序
			 */
			sortPostProcessors(currentRegistryProcessors, beanFactory);

			/**
			 * 合并Processors，为什么要合并，因为registryProcessors是装载BeanDefinitionRegistryPostProcessor的集合
			 * 一开始的时候，spring只会执行BeanDefinitionRegistryPostProcessor独有的方法
			 * 而不会执行BeanDefinitionRegistryPostProcessor父类的方法，即BeanFactoryPostProcessor的方法
			 * 所以这里需要把处理器放入一个集合中，后续统一执行父类的postProcessBeanFactory()方法
			 */
			registryProcessors.addAll(currentRegistryProcessors);

			/**
			 * 可以理解为执行ConfigurationClassPostProcessor的postProcessBeanDefinitionRegistry()方法
			 * 用于进行bean定义的加载 比如我们的包扫描，@Component @import  等等。。。。。。。。。
			 * 调用此方法就完成了扫描@Component的Bean的操作
			 * Spring热插拔的体现，像ConfigurationClassPostProcessor就相当于一个组件，Spring很多事情就是交给组件去管理
			 * 如果不想用这个组件，直接把注册组件的那一步去掉就可以
			 */
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());

			//调用完之后，马上clea掉，后边还需要处理其他类型的BeanDefinitionRegistryPostProcessor
			currentRegistryProcessors.clear();
			//----------------第②步：调用内置实现PriorityOrdered接口BeanDefinitionRegistryPostProcessor完毕--优先级No2-End-----------------/


			//----------------第③步：调用内置实现Ordered接口BeanDefinitionRegistryPostProcessor开始--优先级No3-Begin--------------/
			/**
			 * 再次根据BeanDefinitionRegistryPostProcessor获得BeanName，看这个BeanName是否已经被执行过了，有没有实现Ordered接口
			 * 在这里才可以获取到我们自己定义的实现了BeanDefinitionRegistryPostProcessor的Bean
			 */
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				/**
				 * processedBeans.contains(ppName) 这句代码判断当前BeanDefinitionRegistryPostProcessor是否已经执行过
				 *  beanFactory.isTypeMatch(ppName, Ordered.class) 这句代码判断当前BeanDefinitionRegistryPostProcessor是否实现了Ordered即接口
				 */
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {

					/**
					 * 如果没有被执行过，也实现了Ordered接口的话，把对象放到临时集合currentRegistryProcessors中，名称添加到processedBeans集合中
					 */
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					processedBeans.add(ppName);
				}
			}

			/**
			 * 对currentRegistryProcessors集合中BeanDefinitionRegistryPostProcessor进行排序
			 */
			sortPostProcessors(currentRegistryProcessors, beanFactory);

			/**
			 * 同上进行合并Processors进行后续统一执行BeanDefinitionRegistryPostProcessor父类的方法
			 * 即postProcessBeanFactory()方法
			 */
			registryProcessors.addAll(currentRegistryProcessors);

			/**
			 * 可以理解为执行BeanDefinitionRegistryPostProcessor实现类（并且实现了Ordered接口）的postProcessBeanDefinitionRegistry()方法
			 * 用于进行bean定义的加载 比如我们的包扫描，@import  等等。。。。。。。。。
			 */
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());

			/**
			 * 清除postProcessBeanDefinitionRegistry临时集合
			 */
			currentRegistryProcessors.clear();
			//--------------第③步：调用内置实现Ordered接口BeanDefinitionRegistryPostProcessor开始--优先级No3-End----------------/


			//--------------第④步：调用内置其他BeanDefinitionRegistryPostProcessor开始--优先级No4-Begin----------------/
			//死循环调用所有其他BeanDefinitionRegistryPostProcessors函数，直到不再出现其他BeanDefinitionRegistryPostProcessors函数
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;

				//再次根据BeanDefinitionRegistryPostProcessor获得BeanName
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				for (String ppName : postProcessorNames) {

					//processedBeans.contains(ppName) 这句代码判断当前BeanDefinitionRegistryPostProcessor是否已经执行过
					if (!processedBeans.contains(ppName)) {

						//如果没有执行过把对象放到临时集合currentRegistryProcessors中，名称添加到processedBeans集合中
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						processedBeans.add(ppName);
						reiterate = true;
					}
				}

				/**
				 * 对currentRegistryProcessors集合中BeanDefinitionRegistryPostProcessor进行排序
				 */
				sortPostProcessors(currentRegistryProcessors, beanFactory);

				/**
				 * 同上进行合并Processors进行后续统一执行BeanDefinitionRegistryPostProcessor父类BeanFactoryPostProcessor()的方法
				 * 即postProcessBeanFactory()方法
				 */
				registryProcessors.addAll(currentRegistryProcessors);

				/**
				 * 可以理解为执行BeanDefinitionRegistryPostProcessor其他实现类的postProcessBeanDefinitionRegistry()方法
				 * 用于进行bean定义的加载 比如我们的包扫描，@import  等等。。。。。。。。。
				 */
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
				//执行完毕清理postProcessBeanDefinitionRegistry集合
				currentRegistryProcessors.clear();
			}
			//--------------第④步：调用内置其他BeanDefinitionRegistryPostProcessor开始--优先级No4-End--------------/


			//--------------第⑤步：调用BeanDefinitionRegistryPostProcessor父类BeanFactoryPostProcessor的方法开始--优先级No5-End--------------/
			/**
			 * registryProcessors集合装载BeanDefinitionRegistryPostProcessor
			 * 上面的代码是执行BeanDefinitionRegistryPostProcessor类独有的方法
			 * 这里需要再把父类BeanFactoryPostProcessor的方法也执行一次
			 * 即：调用 BeanDefinitionRegistryPostProcessor.postProcessBeanFactory方法
			 *
			 * ConfigurationClassPostProcess.postProcessBeanFactory方法主要是对@Configuration做代理增强
			 */
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);

			/**
			 * 执行自定义BeanFactoryPostProcessor方法
			 * regularPostProcessors装载BeanFactoryPostProcessor，执行BeanFactoryPostProcessor的方法
			 * 但是regularPostProcessors一般情况下，是不会有数据的，只有在外面手动添加BeanFactoryPostProcessor，才会有数据
			 */
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
			//--------------第⑤步：调用内置其他BeanDefinitionRegistryPostProcessor开始--优先级No3-End--------------/
		}

		else {
			/**
			 * 若当前的beanFactory没有实现了BeanDefinitionRegistry 说明没有注册Bean定义的能力
			 * 那么就直接调用BeanDefinitionRegistryPostProcessor.postProcessBeanFactory()方法
			 */
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}
		/**========第一步END：至此所有调用BeanDefinitionRegistryPostProcessor的后置处理器执行完毕 End ==========*/


		/** ========第二步Begin：调用BeanFactoryPostProcessor的后置处理器 Begin ==========*/
		//根据BeanFactoryPostProcessor获得BeanName
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// 定义保存实现了priorityOrdered的BeanFactoryPostProcessor接口集合
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();

		// 定义保存实现了Ordered的BeanFactoryPostProcessor接口的BeanName集合
		List<String> orderedPostProcessorNames = new ArrayList<>();

		//定义保存其他实现了BeanFactoryPostProcessor接口集合
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		for (String ppName : postProcessorNames) {

			//判断的当前BeanFactoryPostProcessor是否已经在上面调用过
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}

			//如果当前BeanFactoryPostProcessor实现了PriorityOrdered接口则添加到priorityOrderedPostProcessors集合
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			//如果当前BeanFactoryPostProcessor实现了Ordered接口则将BeanName添加到orderedPostProcessorNames集合
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			//否则添加到nonOrderedPostProcessorNames集合中
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		//--------------第①步：调用实现了PriorityOrdered接口的BeanFactoryPostProcessor开始--优先级No1-Begin--------------/
		// 排序处理priorityOrderedPostProcessors，即实现了PriorityOrdered接口的BeanFactoryPostProcessor
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);

		//执行实现了PriorityOrdered接口BeanFactoryPostProcessor.postProcessBeanFactory()方法
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);
		//--------------第①步：调用实现了PriorityOrdered接口的BeanFactoryPostProcessor开始--优先级No1-End--------------/


		//--------------第②步：调用实现了Ordered接口的BeanFactoryPostProcessor开始--优先级No2-Begin--------------/
		// 定义保存实现了Ordered的BeanFactoryPostProcessor接口集合
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		/**
		 * 获取实现了Ordered的BeanFactoryPostProcessor的实例
		 * 并添加到orderedPostProcessors集合
		 */
		for (String postProcessorName : orderedPostProcessorNames) {
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}

		//排序
		sortPostProcessors(orderedPostProcessors, beanFactory);

		//执行实现了Ordered接口BeanFactoryPostProcessor.postProcessBeanFactory()方法
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);
		//--------------第②步：调用实现了PriorityOrdered接口的BeanFactoryPostProcessor开始--优先级No2-end--------------/


		//--------------第③步：调用其他BeanFactoryPostProcessor开始--优先级No3-Begin--------------/
		// 最后，调用所有其他BeanFactoryPostProcessors函数
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}

		//执行其他所有BeanFactoryPostProcessor.postProcessBeanFactory()方法
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);
		//--------------第③步：调用其他BeanFactoryPostProcessor开始--优先级No3-end--------------/

		// 清除缓存的合并bean定义，因为后处理程序可能修改了原始元数据，例如替换值中的占位符……
		beanFactory.clearMetadataCache();
		//==============第二步End：至此所有BeanFactoryPostProcessor调用完毕 --End==============

		//==============至此所有BeanFactoryPostProcessor和BeanDefinitionRegistryPostProcessor调用完毕 --End==============
	}

	/**
	 * 给我们容器中注册了我们bean的后置处理器
	 * bean的后置处理器在什么时候进行调用？在bean的各个生命周期中都会进行调用
	 * 1、注册BeanPostProcessorChecker的后置处理器
	 * 2、注册扫描出来的BeanPostProcessor到BeanFactory.beanPostProcessors集合中（实现了PriorityOrdered接口的）
	 * 3、注册扫描出来的BeanPostProcessor到BeanFactory.beanPostProcessors集合中（实现了Ordered接口的）
	 * 4、注册扫描出来的BeanPostProcessor到BeanFactory.beanPostProcessors集合中（普通的没有实现任何排序接口的）
	 * 5、注册扫描出来的BeanPostProcessor到BeanFactory.beanPostProcessors集合中（实现了MergedBeanDefinitionPostProcessor接口的后置处理器；bean 合并后的处理）
	 * 6、注册ApplicationListenerDetector 应用监听器探测器的后置处理器
	 * @param beanFactory
	 * @param applicationContext
	 */
	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		//去容器中获取所有的BeanPostProcessor 的名称(此时还是bean定义未实例化)
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		/**
		 * bean的后置处理器的个数 beanFactory.getBeanPostProcessorCount()成品的个数：之前refresh-->prepareBeanFactory()中注册的
		 * postProcessorNames.length  beanFactory工厂中bean定义的个数
		 * +1 在后面又马上注册了BeanPostProcessorChecker的后置处理器
		 */
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		/**
		 * 按照BeanPostProcessor实现的优先级接口来分离我们的后置处理器
		 */
		//保存实现了priorityOrdered接口的
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		//系统内部的
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		//实现了我们ordered接口的
		List<String> orderedPostProcessorNames = new ArrayList<>();
		//没有优先级的
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		//循环我们的bean定义(BeanPostProcessor)
		for (String ppName : postProcessorNames) {
			//若实现了PriorityOrdered接口的
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				//显示的调用getBean流程创建bean的后置处理器
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				//加入到集合中
				priorityOrderedPostProcessors.add(pp);
				//判断是否实现了MergedBeanDefinitionPostProcessor
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					//加入到集合中
					internalPostProcessors.add(pp);
				}
			}
			//判断是否实现了Ordered
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			//没有任何拍下接口的
			else {
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// 把实现了priorityOrdered注册到容器中
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// 处理实现Ordered的bean后置处理器
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			//显示调用getBean方法
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			//加入到集合中
			orderedPostProcessors.add(pp);
			//判断是否实现了MergedBeanDefinitionPostProcessor
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				//加入到集合中
				internalPostProcessors.add(pp);
			}
		}
		//排序并且注册我们实现了Order接口的后置处理器
		sortPostProcessors(orderedPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// 实例化我们所有的非排序接口的
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		for (String ppName : nonOrderedPostProcessorNames) {
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			nonOrderedPostProcessors.add(pp);
			//判断是否实现了MergedBeanDefinitionPostProcessor
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}

		//注册我们普通的没有实现任何排序接口的
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		//注册.MergedBeanDefinitionPostProcessor类型的后置处理器 bean 合并后的处理， Autowired 注解正是通过此方法实现诸如类型的预解析。
		sortPostProcessors(internalPostProcessors, beanFactory);
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		//注册ApplicationListenerDetector 应用监听器探测器的后置处理器
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry, ApplicationStartup applicationStartup) {

		//获取容器中的ConfigurationClassPostProcessor的后置处理器进行bean定义的扫描
		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanDefRegistry = applicationStartup.start("spring.context.beandef-registry.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanDefinitionRegistry(registry);
			postProcessBeanDefRegistry.end();
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanFactory = beanFactory.getApplicationStartup().start("spring.context.bean-factory.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanFactory(beanFactory);
			postProcessBeanFactory.end();
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		if (beanFactory instanceof AbstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			((AbstractBeanFactory) beanFactory).addBeanPostProcessors(postProcessors);
		}
		else {
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
