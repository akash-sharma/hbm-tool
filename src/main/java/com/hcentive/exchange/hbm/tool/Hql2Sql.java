package com.hcentive.exchange.hbm.tool;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;

import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.MappedSuperclass;

import org.hibernate.Filter;
import org.hibernate.MappingException;
import org.hibernate.Session;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.internal.StandardServiceRegistryImpl;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.engine.query.spi.EntityGraphQueryHint;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.hql.internal.QuerySplitter;
import org.hibernate.hql.spi.FilterTranslator;
import org.hibernate.hql.spi.QueryTranslator;
import org.hibernate.hql.spi.QueryTranslatorFactory;
import org.hibernate.internal.util.collections.ArrayHelper;
import org.hibernate.internal.util.config.ConfigurationHelper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;

public class Hql2Sql {

	private static final String CUSTOM_HQL = "FROM SsapEligibility";

	private static final String CLASSPATH_ALL_URL_PREFIX = "classpath*:";
	private static final String RESOURCE_PATTERN = "/**/*.class";
	private static final String PACKAGE_INFO_SUFFIX = ".package-info";
	private static final Set<TypeFilter> entityTypeFilters;

	static {
		entityTypeFilters = new LinkedHashSet<TypeFilter>(4);
		entityTypeFilters.add(new AnnotationTypeFilter(Entity.class, false));
		entityTypeFilters.add(new AnnotationTypeFilter(Embeddable.class, false));
		entityTypeFilters.add(new AnnotationTypeFilter(MappedSuperclass.class, false));
		try {
			@SuppressWarnings("unchecked")
			Class<? extends Annotation> converterAnnotation = (Class<? extends Annotation>) Hql2Sql.class
					.getClassLoader().loadClass("javax.persistence.Converter");
			entityTypeFilters.add(new AnnotationTypeFilter(converterAnnotation, false));
		} catch (ClassNotFoundException ex) {
			// JPA 2.1 API not available - Hibernate <4.3
		}
	}

	/**
	 * @see org.hibernate.engine.query.spi.HQLQueryPlan
	 * @param hql
	 * @param factory
	 */
	private static void printSql(String hql, SessionFactoryImplementor factory) {

		EntityGraphQueryHint entityGraphQueryHint = null;
		boolean shallow = false;
		String collectionRole = null;
		Map<String, Filter> enabledFilters = new HashMap<String, Filter>();

		final String[] concreteQueryStrings = QuerySplitter.concreteQueries(hql, factory);
		final int length = concreteQueryStrings.length;
		QueryTranslator[] translators = new QueryTranslator[length];

		final List<String> sqlStringList = new ArrayList<String>();
		final Set<Serializable> combinedQuerySpaces = new HashSet<Serializable>();

		final boolean hasCollectionRole = (collectionRole == null);
		final Map querySubstitutions = factory.getSettings().getQuerySubstitutions();
		final QueryTranslatorFactory queryTranslatorFactory = factory.getSettings().getQueryTranslatorFactory();

		for (int i = 0; i < length; i++) {
			if (hasCollectionRole) {
				translators[i] = queryTranslatorFactory.createQueryTranslator(hql, concreteQueryStrings[i],
						enabledFilters, factory, entityGraphQueryHint);
				translators[i].compile(querySubstitutions, shallow);
			} else {
				translators[i] = queryTranslatorFactory.createFilterTranslator(hql, concreteQueryStrings[i],
						enabledFilters, factory);
				((FilterTranslator) translators[i]).compile(collectionRole, querySubstitutions, shallow);
			}
			combinedQuerySpaces.addAll(translators[i].getQuerySpaces());
			sqlStringList.addAll(translators[i].collectSqlStrings());
		}
		String[] sqlStrings = ArrayHelper.toStringArray(sqlStringList);
		for (String sql : sqlStrings) {
			System.out.println(sql);
		}
	}

	@SuppressWarnings("deprecation")
	public static void main(String args[]) {

		Session session = null;
		try {
			Configuration cfg = new Configuration();
			cfg.configure("/custom-cfg-hbm-tool.xml");
			StandardServiceRegistryImpl serviceRegistry = createServiceRegistry(cfg.getProperties());
			List<String> packageToScan = new ArrayList<String>();
			packageToScan.add((String) cfg.getProperties().get("packagesToScan"));
			loadClasses(cfg, packageToScan);
			SessionFactoryImplementor sfi = (SessionFactoryImplementor) cfg.buildSessionFactory(serviceRegistry);
			System.out.println("sfi :" + sfi);
			if (sfi == null) {
				System.out.println("sfi cannot be null");
			} else {
				printSqlFromUserInput(sfi);
				// executeCustomHql(sfi);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (session != null) {
				session.getTransaction().commit();
				session.close();
			}
		}
	}

	private static void executeCustomHql(SessionFactoryImplementor sfi) {
		Session session = sfi.openSession();
		session.beginTransaction();
		session.createQuery(CUSTOM_HQL).list();
		session.getTransaction().commit();
		session.close();
	}

	private static void printSqlFromUserInput(SessionFactoryImplementor factory) {

		Scanner scanner = new Scanner(System.in);
		while (true) {
			System.out.println("Enter Hql query or press Q/q to exit :");
			String inputStr = scanner.nextLine();
			if (inputStr.equalsIgnoreCase("Q")) {
				System.exit(0);
			} else {
				try {
					printSql(inputStr, factory);
				} catch (Exception e) {
					System.out.println(e);
				}
			}
		}
	}

	static StandardServiceRegistryImpl createServiceRegistry(Properties properties) {
		Environment.verifyProperties(properties);
		ConfigurationHelper.resolvePlaceHolders(properties);
		return (StandardServiceRegistryImpl) new StandardServiceRegistryBuilder().applySettings(properties).build();
	}

	// see Configuration.addAnnotatedClass(Class annotatedClass)
	 static void loadClasses(Configuration cfg, List<String> packagesToScan) {
		Set<String> classNames = new TreeSet<String>();
		Set<String> packageNames = new TreeSet<String>();

		ResourcePatternResolver resourcePatternResolver = ResourcePatternUtils
				.getResourcePatternResolver(new PathMatchingResourcePatternResolver());

		try {
			for (String pkg : packagesToScan) {
				String pattern = CLASSPATH_ALL_URL_PREFIX + ClassUtils.convertClassNameToResourcePath(pkg)
						+ RESOURCE_PATTERN;
				Resource[] resources = resourcePatternResolver.getResources(pattern);
				MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resourcePatternResolver);
				for (Resource resource : resources) {
					if (resource.isReadable()) {
						MetadataReader reader = readerFactory.getMetadataReader(resource);
						String className = reader.getClassMetadata().getClassName();
						if (matchesEntityTypeFilter(reader, readerFactory)) {
							classNames.add(className);
						} else if (className.endsWith(PACKAGE_INFO_SUFFIX)) {
							packageNames.add(className.substring(0, className.length() - PACKAGE_INFO_SUFFIX.length()));
						}
					}
				}
			}
		} catch (IOException ex) {
			throw new MappingException("Failed to scan classpath for unlisted classes", ex);
		}
		try {
			String packageToSkip = (String) cfg.getProperties().get("packageToSkip");

			for (String className : classNames) {
				if (packageToSkip.isEmpty()) {
					cfg.addAnnotatedClass(Class.forName(className));
				} else if (!className.contains(packageToSkip)) {
					cfg.addAnnotatedClass(Class.forName(className));
				}
			}
			for (String packageName : packageNames) {
				cfg.addPackage(packageName);
			}
		} catch (ClassNotFoundException ex) {
			throw new MappingException("Failed to load annotated classes from classpath", ex);
		}
	}

	private static boolean matchesEntityTypeFilter(MetadataReader reader, MetadataReaderFactory readerFactory)
			throws IOException {
		for (TypeFilter filter : entityTypeFilters) {
			if (filter.match(reader, readerFactory)) {
				return true;
			}
		}
		return false;
	}
}