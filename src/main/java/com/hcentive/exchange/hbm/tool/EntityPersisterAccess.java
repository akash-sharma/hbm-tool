package com.hcentive.exchange.hbm.tool;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.internal.StandardServiceRegistryImpl;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;

import com.hcentive.eligibility.ssap.domain.SsapEligibility;

//http://stackoverflow.com/questions/21327067/how-to-get-dialect-specific-sql-for-a-hibernate-entity

public class EntityPersisterAccess {

	private SessionFactoryImplementor sessionFactoryImplementor;

	public EntityPersisterAccess(SessionFactory sessionFactory) {
		if (!(sessionFactory instanceof SessionFactoryImplementor)) {
			throw new IllegalArgumentException("EntityPersisterAccess only works with a "
					+ SessionFactoryImplementor.class);
		}
		this.sessionFactoryImplementor = (SessionFactoryImplementor) sessionFactory;
	}

	public String getInsertSql(Class<?> entityClass) {
		return getInsertSql(entityClass.getCanonicalName());
	}

	public String getInsertSql(String entityName) {
		String insertSql = null;
		EntityPersister ep = sessionFactoryImplementor.getEntityPersister(entityName);
		if (ep instanceof AbstractEntityPersister) {
			AbstractEntityPersister aep = (AbstractEntityPersister) ep;
			boolean[] includeProperty = aep.getPropertyInsertability();

			java.lang.reflect.Method method = null;
			try {
				Class classToCall = null;
				try {
					classToCall = Class.forName("org.hibernate.persister.entity.AbstractEntityPersister");
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}
				for(java.lang.reflect.Method tempMethod : classToCall.getMethods()) {
					if(tempMethod.getName().contains("generateInsertString")) {
						method = tempMethod;
						break;
					}
				}
				//method = classToCall.getDeclaredMethod("generateInsertString", Boolean.class, boolean[].class);
			} catch (SecurityException e) {
				e.printStackTrace();
			}

			try {
				insertSql = (String) method.invoke(aep, true, includeProperty);
			} catch (IllegalArgumentException e) {
			} catch (IllegalAccessException e) {
			} catch (InvocationTargetException e) {
			}

			// insertSql = aep.generateInsertString(true, includeProperty);
		}
		return insertSql;
	}

	public static void main(String args[]) {
		Configuration cfg = new Configuration();
		cfg.configure("/custom-cfg-hbm-tool.xml");
		StandardServiceRegistryImpl serviceRegistry = Hql2Sql.createServiceRegistry(cfg.getProperties());
		List<String> packageToScan = new ArrayList<String>();
		packageToScan.add((String) cfg.getProperties().get("packagesToScan"));
		Hql2Sql.loadClasses(cfg, packageToScan);
		SessionFactory sessionFactory = cfg.buildSessionFactory(serviceRegistry);
		EntityPersisterAccess epa = new EntityPersisterAccess(sessionFactory);
		String insertSql = epa.getInsertSql(SsapEligibility.class); // some
																	// entity
																	// class
		System.out.println(insertSql);
	}

}