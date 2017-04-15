package com.hcentive.exchange.hbm.tool;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.MappedSuperclass;

import org.hibernate.HibernateException;
import org.hibernate.MappingException;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.internal.StandardServiceRegistryImpl;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.jdbc.spi.SqlExceptionHelper;
import org.hibernate.internal.util.config.ConfigurationHelper;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.tool.hbm2ddl.ConnectionHelper;
import org.hibernate.tool.hbm2ddl.DatabaseMetadata;
import org.hibernate.tool.hbm2ddl.SchemaUpdateScript;
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

public class SchemaUpdate {

	private final Configuration configuration;
	private final ConnectionHelper connectionHelper;
	private final Dialect dialect;

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
			Class<? extends Annotation> converterAnnotation = (Class<? extends Annotation>) SchemaUpdate.class
					.getClassLoader().loadClass("javax.persistence.Converter");
			entityTypeFilters.add(new AnnotationTypeFilter(converterAnnotation, false));
		} catch (ClassNotFoundException ex) {
			// JPA 2.1 API not available - Hibernate <4.3
		}
	}

	@SuppressWarnings("deprecation")
	public SchemaUpdate(ServiceRegistry serviceRegistry, Configuration cfg) throws HibernateException {
		this.configuration = cfg;

		final JdbcServices jdbcServices = serviceRegistry.getService(JdbcServices.class);
		this.dialect = jdbcServices.getDialect();
		this.connectionHelper = new SuppliedConnectionProviderConnectionHelper(jdbcServices.getConnectionProvider());
	}

	private static StandardServiceRegistryImpl createServiceRegistry(Properties properties) {
		Environment.verifyProperties(properties);
		ConfigurationHelper.resolvePlaceHolders(properties);
		return (StandardServiceRegistryImpl) new StandardServiceRegistryBuilder().applySettings(properties).build();
	}

	public static void main(String[] args) {
		try {
			Configuration cfg = new Configuration();
			cfg.configure("/custom-cfg-hbm-tool.xml");
			StandardServiceRegistryImpl serviceRegistry = createServiceRegistry(cfg.getProperties());
			List<String> packageToScan = new ArrayList<String>();
			packageToScan.add((String) cfg.getProperties().get("packagesToScan"));
			loadClasses(cfg, packageToScan);
			cfg.buildSessionFactory(serviceRegistry);
			try {
				new SchemaUpdate(serviceRegistry, cfg).execute();
			} finally {
				serviceRegistry.destroy();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Execute the schema updates
	 *
	 * @param script
	 *            print all DDL to the console
	 */
	public void execute() {

		Connection connection = null;
		DatabaseMetadata meta = null;
		try {
			connectionHelper.prepare(true);
			connection = connectionHelper.getConnection();
			meta = new DatabaseMetadata(connection, dialect, configuration);
			List<SchemaUpdateScript> scripts = configuration.generateSchemaUpdateScriptList(dialect, meta);
			System.out.println("<----------------SchemaUpdate script output starts from here--------------->");
			for (SchemaUpdateScript script : scripts) {
				System.out.println(script.getScript());
			}
		} catch (SQLException sqle) {
			System.out.println(sqle);
		} finally {
			try {
				connectionHelper.release();
			} catch (Exception e) {
				System.out.println(e);
			}
		}
	}

	// see Configuration.addAnnotatedClass(Class annotatedClass)
	private static void loadClasses(Configuration cfg, List<String> packagesToScan) {
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

class ManagedProviderConnectionHelper implements ConnectionHelper {
	private Properties cfgProperties;
	private StandardServiceRegistryImpl serviceRegistry;
	private Connection connection;

	public ManagedProviderConnectionHelper(Properties cfgProperties) {
		this.cfgProperties = cfgProperties;
	}

	public void prepare(boolean needsAutoCommit) throws SQLException {
		serviceRegistry = createServiceRegistry(cfgProperties);
		connection = serviceRegistry.getService(ConnectionProvider.class).getConnection();
		if (needsAutoCommit && !connection.getAutoCommit()) {
			connection.commit();
			connection.setAutoCommit(true);
		}
	}

	private static StandardServiceRegistryImpl createServiceRegistry(Properties properties) {
		Environment.verifyProperties(properties);
		ConfigurationHelper.resolvePlaceHolders(properties);
		return (StandardServiceRegistryImpl) new StandardServiceRegistryBuilder().applySettings(properties).build();
	}

	public Connection getConnection() throws SQLException {
		return connection;
	}

	public void release() throws SQLException {
		try {
			releaseConnection();
		} finally {
			releaseServiceRegistry();
		}
	}

	private void releaseConnection() throws SQLException {
		if (connection != null) {
			try {
				new SqlExceptionHelper().logAndClearWarnings(connection);
			} finally {
				try {
					serviceRegistry.getService(ConnectionProvider.class).closeConnection(connection);
				} finally {
					connection = null;
				}
			}
		}
	}

	private void releaseServiceRegistry() {
		if (serviceRegistry != null) {
			try {
				serviceRegistry.destroy();
			} finally {
				serviceRegistry = null;
			}
		}
	}
}

class SuppliedConnectionProviderConnectionHelper implements ConnectionHelper {
	private ConnectionProvider provider;
	private Connection connection;
	private boolean toggleAutoCommit;

	public SuppliedConnectionProviderConnectionHelper(ConnectionProvider provider) {
		this.provider = provider;
	}

	public void prepare(boolean needsAutoCommit) throws SQLException {
		connection = provider.getConnection();
		toggleAutoCommit = needsAutoCommit && !connection.getAutoCommit();
		if (toggleAutoCommit) {
			try {
				connection.commit();
			} catch (Throwable ignore) {
				// might happen with a managed connection
			}
			connection.setAutoCommit(true);
		}
	}

	public Connection getConnection() throws SQLException {
		return connection;
	}

	public void release() throws SQLException {
		// we only release the connection
		if (connection != null) {
			new SqlExceptionHelper().logAndClearWarnings(connection);
			if (toggleAutoCommit) {
				connection.setAutoCommit(false);
			}
			provider.closeConnection(connection);
			connection = null;
		}
	}
}