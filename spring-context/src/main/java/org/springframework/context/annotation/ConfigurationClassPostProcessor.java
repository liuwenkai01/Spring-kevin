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

package org.springframework.context.annotation;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.PassThroughSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ApplicationStartupAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ConfigurationClassEnhancer.EnhancedConfiguration;
import org.springframework.core.NativeDetector;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import java.util.*;

/**
 * {@link BeanFactoryPostProcessor} used for bootstrapping processing of
 * {@link Configuration @Configuration} classes.
 *
 * <p>Registered by default when using {@code <context:annotation-config/>} or
 * {@code <context:component-scan/>}. Otherwise, may be declared manually as
 * with any other {@link BeanFactoryPostProcessor}.
 *
 * <p>This post processor is priority-ordered as it is important that any
 * {@link Bean @Bean} methods declared in {@code @Configuration} classes have
 * their corresponding bean definitions registered before any other
 * {@code BeanFactoryPostProcessor} executes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 3.0
 */
public class ConfigurationClassPostProcessor implements BeanDefinitionRegistryPostProcessor,
		PriorityOrdered, ResourceLoaderAware, ApplicationStartupAware, BeanClassLoaderAware, EnvironmentAware {

	/**
	 * A {@code BeanNameGenerator} using fully qualified class names as default bean names.
	 * <p>This default for configuration-level import purposes may be overridden through
	 * {@link #setBeanNameGenerator}. Note that the default for component scanning purposes
	 * is a plain {@link AnnotationBeanNameGenerator#INSTANCE}, unless overridden through
	 * {@link #setBeanNameGenerator} with a unified user-level bean name generator.
	 * @since 5.2
	 * @see #setBeanNameGenerator
	 */
	public static final AnnotationBeanNameGenerator IMPORT_BEAN_NAME_GENERATOR =
			FullyQualifiedAnnotationBeanNameGenerator.INSTANCE;

	private static final String IMPORT_REGISTRY_BEAN_NAME =
			ConfigurationClassPostProcessor.class.getName() + ".importRegistry";


	private final Log logger = LogFactory.getLog(getClass());

	private SourceExtractor sourceExtractor = new PassThroughSourceExtractor();

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	@Nullable
	private Environment environment;

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	private MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

	private boolean setMetadataReaderFactoryCalled = false;

	private final Set<Integer> registriesPostProcessed = new HashSet<>();

	private final Set<Integer> factoriesPostProcessed = new HashSet<>();

	@Nullable
	private ConfigurationClassBeanDefinitionReader reader;

	private boolean localBeanNameGeneratorSet = false;

	/* Using short class names as default bean names by default. */
	private BeanNameGenerator componentScanBeanNameGenerator = AnnotationBeanNameGenerator.INSTANCE;

	/* Using fully qualified class names as default bean names by default. */
	private BeanNameGenerator importBeanNameGenerator = IMPORT_BEAN_NAME_GENERATOR;

	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;


	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;  // within PriorityOrdered
	}

	/**
	 * Set the {@link SourceExtractor} to use for generated bean definitions
	 * that correspond to {@link Bean} factory methods.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new PassThroughSourceExtractor());
	}

	/**
	 * Set the {@link ProblemReporter} to use.
	 * <p>Used to register any problems detected with {@link Configuration} or {@link Bean}
	 * declarations. For instance, an @Bean method marked as {@code final} is illegal
	 * and would be reported as a problem. Defaults to {@link FailFastProblemReporter}.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Set the {@link MetadataReaderFactory} to use.
	 * <p>Default is a {@link CachingMetadataReaderFactory} for the specified
	 * {@linkplain #setBeanClassLoader bean class loader}.
	 */
	public void setMetadataReaderFactory(MetadataReaderFactory metadataReaderFactory) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.setMetadataReaderFactoryCalled = true;
	}

	/**
	 * Set the {@link BeanNameGenerator} to be used when triggering component scanning
	 * from {@link Configuration} classes and when registering {@link Import}'ed
	 * configuration classes. The default is a standard {@link AnnotationBeanNameGenerator}
	 * for scanned components (compatible with the default in {@link ClassPathBeanDefinitionScanner})
	 * and a variant thereof for imported configuration classes (using unique fully-qualified
	 * class names instead of standard component overriding).
	 * <p>Note that this strategy does <em>not</em> apply to {@link Bean} methods.
	 * <p>This setter is typically only appropriate when configuring the post-processor as a
	 * standalone bean definition in XML, e.g. not using the dedicated {@code AnnotationConfig*}
	 * application contexts or the {@code <context:annotation-config>} element. Any bean name
	 * generator specified against the application context will take precedence over any set here.
	 * @since 3.1.1
	 * @see AnnotationConfigApplicationContext#setBeanNameGenerator(BeanNameGenerator)
	 * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		Assert.notNull(beanNameGenerator, "BeanNameGenerator must not be null");
		this.localBeanNameGeneratorSet = true;
		this.componentScanBeanNameGenerator = beanNameGenerator;
		this.importBeanNameGenerator = beanNameGenerator;
	}

	@Override
	public void setEnvironment(Environment environment) {
		Assert.notNull(environment, "Environment must not be null");
		this.environment = environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(resourceLoader);
		}
	}

	@Override
	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(beanClassLoader);
		}
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		this.applicationStartup = applicationStartup;
	}

	/**
	 * Derive further bean definitions from the configuration classes in the registry.
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		int registryId = System.identityHashCode(registry);
		if (this.registriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanDefinitionRegistry already called on this post-processor against " + registry);
		}
		if (this.factoriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + registry);
		}
		this.registriesPostProcessed.add(registryId);
		//真正的解析我们的bean定义
		processConfigBeanDefinitions(registry);
	}

	/**
	 * Prepare the Configuration classes for servicing bean requests at runtime
	 * by replacing them with CGLIB-enhanced subclasses.
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		int factoryId = System.identityHashCode(beanFactory);
		if (this.factoriesPostProcessed.contains(factoryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + beanFactory);
		}
		this.factoriesPostProcessed.add(factoryId);
		if (!this.registriesPostProcessed.contains(factoryId)) {
			// BeanDefinitionRegistryPostProcessor hook apparently not supported...
			// Simply call processConfigurationClasses lazily at this point then.
			processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory);
		}

		// 使用cglib对配置类进行代理，因为@Bean方法到时候要进行创建Bean的实例
		enhanceConfigurationClasses(beanFactory);
		beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
	}

	/**
	 * Build and validate a configuration model based on the registry of
	 * {@link Configuration} classes.
	 */
	public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {

		//定义存放配置类BeanDefinitionHolder的集合
		List<BeanDefinitionHolder> configCandidates = new ArrayList<>();

		//获得所有的BeanDefinition的Name，放入candidateNames数组
		String[] candidateNames = registry.getBeanDefinitionNames();

		//循环我们的上一步获取的所有的bean定义信息：找出配置类（添加@Configuration）添加到configCandidates集合
		for (String beanName : candidateNames) {

			//通过bean的名称来获取我们的bean定义对象
			BeanDefinition beanDef = registry.getBeanDefinition(beanName);

			/**
			 * 判断当前BeanDefinition
			 * org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass属性是否存在
			 * （第一次进来还没有解析；逻辑 CONFIGURATION_CLASS_ATTRIBUTE属性CONFIGURATION_CLASS_ATTRIBUTE是为Null的,解析后才会设置该属性）
			 *
			 * ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE
			 * 该方法通过我们传入的bean定义的字段 来判断当前的bean是完全的配置类(标注了@Configuration注解了没有)
			 *
			 * 最终会通过方法checkConfigurationClassCandidate 来设置CONFIGURATION_CLASS_ATTRIBUTE的属性是完全的配置类还是非正式的配置类
			 *
			 * PS:
			 * 内部有两个标记位来标记是否已经处理过了
			 * 这里会引发一连串知识盲点
			 * 当我们注册配置类的时候，可以不加Configuration注解，直接使用@Component @ComponentScan @Import @ImportResource注解，称之为Lite配置类
			 * 如果加了Configuration注解，就称之为Full配置类
			 * 如果我们注册了Lite配置类，我们getBean这个配置类，会发现它就是原本的那个配置类
			 * 如果我们注册了Full配置类，我们getBean这个配置类，会发现它已经不是原本那个配置类了，而是已经被cgilb代理的类了
			 * 写一个A类，其中有一个构造方法，打印出“你好”
			 * 再写一个配置类，里面有两个bean注解的方法
			 * 其中一个方法new了A 类，并且返回A的对象，把此方法称之为getA
			 * 第二个方法又调用了getA方法
			 * 如果配置类是Lite配置类，会发现打印了两次“你好”，也就是说A类被new了两次
			 * 如果配置类是Full配置类，会发现只打印了一次“你好”，也就是说A类只被new了一次，因为这个类被cgilb代理了，方法已经被改写
			 */
			if (beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE) != null) {
				if (logger.isDebugEnabled()) {
					logger.debug("Bean definition has already been processed as a configuration class: " + beanDef);
				}
			}
			/**
			 * 判断是否为配置类（有两种情况 一种是传统意义上的配置类，一种是普通的bean），
			 * 在这个方法内部，会做判断，这个配置类是Full配置类，还是Lite配置类，并且做上标记
			 * 满足条件，加入到configCandidates
			 */
			else if (ConfigurationClassUtils.checkConfigurationClassCandidate(beanDef, this.metadataReaderFactory)) {
				//满足添加 就加入到候选的配置类集合中
				configCandidates.add(new BeanDefinitionHolder(beanDef, beanName));
			}
		}

		// Return immediately if no @Configuration classes were found
		// 若没有找到配置类 直接返回
		if (configCandidates.isEmpty()) {
			return;
		}

		// Sort by previously determined @Order value, if applicable
		//对我们的配置类进行Order排序
		configCandidates.sort((bd1, bd2) -> {
			int i1 = ConfigurationClassUtils.getOrder(bd1.getBeanDefinition());
			int i2 = ConfigurationClassUtils.getOrder(bd2.getBeanDefinition());
			return Integer.compare(i1, i2);
		});

		// Detect any custom bean name generation strategy supplied through the enclosing application context
		SingletonBeanRegistry sbr = null;

		/**DefaultListableBeanFactory最终会实现SingletonBeanRegistry接口，所以可以进入到这个if**/
		if (registry instanceof SingletonBeanRegistry) {
			sbr = (SingletonBeanRegistry) registry;
			if (!this.localBeanNameGeneratorSet) {

				/**
				 * spring中可以修改默认的bean命名方式，这里就是看用户有没有自定义bean命名方式，虽然一般没有人会这么做
				 * 创建我们通过@CompentScan导入进来的bean name的生成器
				 * 创建我们通过@Import导入进来的bean的名称
				 * */
				BeanNameGenerator generator = (BeanNameGenerator) sbr.getSingleton(
						AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR);
				if (generator != null) {
					//设置@CompentScan导入进来的bean的名称生成器(默认类首字母小写）也可以自己定义，一般不会
					this.componentScanBeanNameGenerator = generator;

					//设置@Import导入进来的bean的名称生成器(默认类首字母小写）也可以自己定义，一般不会
					this.importBeanNameGenerator = generator;
				}
			}
		}

		if (this.environment == null) {
			this.environment = new StandardEnvironment();
		}

		// Parse each @Configuration class
		//创建一个配置类解析器对象
		ConfigurationClassParser parser = new ConfigurationClassParser(
				this.metadataReaderFactory, this.problemReporter, this.environment,
				this.resourceLoader, this.componentScanBeanNameGenerator, registry);

		//用于保存我们的配置类BeanDefinitionHolder放入上面筛选出来的配置类
		Set<BeanDefinitionHolder> candidates = new LinkedHashSet<>(configCandidates);
		//用于保存我们的已经解析的配置类，长度默认为解析出来默认的配置类的集合长度
		Set<ConfigurationClass> alreadyParsed = new HashSet<>(configCandidates.size());
		//do while 会进行第一次解析
		do {
			StartupStep processConfig = this.applicationStartup.start("spring.context.config-classes.parse");
			//真正的解析我们的配置类
			parser.parse(candidates);
			parser.validate();

			//解析出来的配置类
			Set<ConfigurationClass> configClasses = new LinkedHashSet<>(parser.getConfigurationClasses());
			configClasses.removeAll(alreadyParsed);

			// 创建配置类BeanDefinition读取器
			if (this.reader == null) {
				this.reader = new ConfigurationClassBeanDefinitionReader(
						registry, this.sourceExtractor, this.resourceLoader, this.environment,
						this.importBeanNameGenerator, parser.getImportRegistry());
			}

			// 此处才把@Bean的方法和@Import 注册到BeanDefinitionMap中
			//把所有的ConfigurationClass加载成BeanDefinition，通过情况下一个配置类会对应一个BeanDefinition,不过也有可能一个配置类对应多个BeanDefinition
			//比如一个配置类中有多个@Bean，一个配置配置了@ImportResource
			this.reader.loadBeanDefinitions(configClasses);
			//加入到已经解析的集合中
			alreadyParsed.addAll(configClasses);
			processConfig.tag("classCount", () -> String.valueOf(configClasses.size())).end();

			candidates.clear();

			//判断我们ioc容器中的是不是>候选原始的bean定义(最开始初始化创建的BeanDefinition)的个数；在执行完上面的扫描之后，一般BeanDefinition肯定是增加了的
			// 如果发现BeanDefinition增加了，则有可能增加了配置类
			if (registry.getBeanDefinitionCount() > candidateNames.length) {

				//获取所有的bean定义
				String[] newCandidateNames = registry.getBeanDefinitionNames();
				//原始的老的候选的bean定义（最初初始化定义的BeanDefinition）
				Set<String> oldCandidateNames = new HashSet<>(Arrays.asList(candidateNames));
				Set<String> alreadyParsedClasses = new HashSet<>();
				//赋值已经解析的
				for (ConfigurationClass configurationClass : alreadyParsed) {
					alreadyParsedClasses.add(configurationClass.getMetadata().getClassName());
				}
				for (String candidateName : newCandidateNames) {
					//表示当前循环的还没有被解析过
					if (!oldCandidateNames.contains(candidateName)) {
						BeanDefinition bd = registry.getBeanDefinition(candidateName);

						//判断有没有被解析过（这里检查的配置类可能不在当前配置类扫描包下；如果配置类在当前配置类扫描包下则会在parse方法里边做递归解析）
						if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, this.metadataReaderFactory) &&
								!alreadyParsedClasses.contains(bd.getBeanClassName())) {
							candidates.add(new BeanDefinitionHolder(bd, candidateName));
						}
					}
				}
				candidateNames = newCandidateNames;
			}
		}
		//存在没有解析过的 需要循环解析
		while (!candidates.isEmpty());

		// 将ImportRegistry注册为bean，以便支持ImportAware @Configuration类
		if (sbr != null && !sbr.containsSingleton(IMPORT_REGISTRY_BEAN_NAME)) {
			sbr.registerSingleton(IMPORT_REGISTRY_BEAN_NAME, parser.getImportRegistry());
		}

		if (this.metadataReaderFactory instanceof CachingMetadataReaderFactory) {
			// Clear cache in externally provided MetadataReaderFactory; this is a no-op
			// for a shared cache since it'll be cleared by the ApplicationContext.
			((CachingMetadataReaderFactory) this.metadataReaderFactory).clearCache();
		}
	}

	/**
	 * Post-processes a BeanFactory in search of Configuration class BeanDefinitions;
	 * any candidates are then enhanced by a {@link ConfigurationClassEnhancer}.
	 * Candidate status is determined by BeanDefinition attribute metadata.
	 * @see ConfigurationClassEnhancer
	 */
	public void enhanceConfigurationClasses(ConfigurableListableBeanFactory beanFactory) {
		StartupStep enhanceConfigClasses = this.applicationStartup.start("spring.context.config-classes.enhance");
		Map<String, AbstractBeanDefinition> configBeanDefs = new LinkedHashMap<>();
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			Object configClassAttr = beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE);
			AnnotationMetadata annotationMetadata = null;
			MethodMetadata methodMetadata = null;
			if (beanDef instanceof AnnotatedBeanDefinition) {
				AnnotatedBeanDefinition annotatedBeanDefinition = (AnnotatedBeanDefinition) beanDef;
				annotationMetadata = annotatedBeanDefinition.getMetadata();
				methodMetadata = annotatedBeanDefinition.getFactoryMethodMetadata();
			}
			if ((configClassAttr != null || methodMetadata != null) && beanDef instanceof AbstractBeanDefinition) {
				// Configuration class (full or lite) or a configuration-derived @Bean method
				// -> eagerly resolve bean class at this point, unless it's a 'lite' configuration
				// or component class without @Bean methods.
				AbstractBeanDefinition abd = (AbstractBeanDefinition) beanDef;
				if (!abd.hasBeanClass()) {
					boolean liteConfigurationCandidateWithoutBeanMethods =
							(ConfigurationClassUtils.CONFIGURATION_CLASS_LITE.equals(configClassAttr) &&
								annotationMetadata != null && !ConfigurationClassUtils.hasBeanMethods(annotationMetadata));
					if (!liteConfigurationCandidateWithoutBeanMethods) {
						try {
							abd.resolveBeanClass(this.beanClassLoader);
						}
						catch (Throwable ex) {
							throw new IllegalStateException(
									"Cannot load configuration class: " + beanDef.getBeanClassName(), ex);
						}
					}
				}
			}

			/*
			只有full版配置类才会创建cglib代理
			虽然我们在指定配置的时候不标注@Configuration也行，所以加不加注解的区别就在这里
			那么加了@Configuration和不加有本质上有什么区别的？
			当在配置类中一个@Bean 使用方法的方式引用另一个Bean如果不加注解就会重复加载Bean
			如果加了@Configuration  则会在这里创建cglib代理，当调用@Bean方法时会先检测容器中是否存在*/
			if (ConfigurationClassUtils.CONFIGURATION_CLASS_FULL.equals(configClassAttr)) {
				if (!(beanDef instanceof AbstractBeanDefinition)) {
					throw new BeanDefinitionStoreException("Cannot enhance @Configuration bean definition '" +
							beanName + "' since it is not stored in an AbstractBeanDefinition subclass");
				}
				else if (logger.isInfoEnabled() && beanFactory.containsSingleton(beanName)) {
					logger.info("Cannot enhance @Configuration bean definition '" + beanName +
							"' since its singleton instance has been created too early. The typical cause " +
							"is a non-static @Bean method with a BeanDefinitionRegistryPostProcessor " +
							"return type: Consider declaring such methods as 'static'.");
				}
				configBeanDefs.put(beanName, (AbstractBeanDefinition) beanDef);
			}
		}
		if (configBeanDefs.isEmpty() || NativeDetector.inNativeImage()) {
			// nothing to enhance -> return immediately
			enhanceConfigClasses.end();
			return;
		}

		ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer();
		for (Map.Entry<String, AbstractBeanDefinition> entry : configBeanDefs.entrySet()) {
			AbstractBeanDefinition beanDef = entry.getValue();
			// If a @Configuration class gets proxied, always proxy the target class
			beanDef.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
			// Set enhanced subclass of the user-specified bean class
			Class<?> configClass = beanDef.getBeanClass();
			Class<?> enhancedClass = enhancer.enhance(configClass, this.beanClassLoader);
			if (configClass != enhancedClass) {
				if (logger.isTraceEnabled()) {
					logger.trace(String.format("Replacing bean definition '%s' existing class '%s' with " +
							"enhanced class '%s'", entry.getKey(), configClass.getName(), enhancedClass.getName()));
				}
				// 重新修改Bean定义的Class，在创建Bean的实例时将会实例cglib的类
				beanDef.setBeanClass(enhancedClass);
			}
		}
		enhanceConfigClasses.tag("classCount", () -> String.valueOf(configBeanDefs.keySet().size())).end();
	}


	private static class ImportAwareBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

		private final BeanFactory beanFactory;

		public ImportAwareBeanPostProcessor(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public PropertyValues postProcessProperties(@Nullable PropertyValues pvs, Object bean, String beanName) {
			// Inject the BeanFactory before AutowiredAnnotationBeanPostProcessor's
			// postProcessProperties method attempts to autowire other configuration beans.
			if (bean instanceof EnhancedConfiguration) {
				((EnhancedConfiguration) bean).setBeanFactory(this.beanFactory);
			}
			return pvs;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			if (bean instanceof ImportAware) {
				ImportRegistry ir = this.beanFactory.getBean(IMPORT_REGISTRY_BEAN_NAME, ImportRegistry.class);
				AnnotationMetadata importingClass = ir.getImportingClassFor(ClassUtils.getUserClass(bean).getName());
				if (importingClass != null) {
					((ImportAware) bean).setImportMetadata(importingClass);
				}
			}
			return bean;
		}
	}

}
