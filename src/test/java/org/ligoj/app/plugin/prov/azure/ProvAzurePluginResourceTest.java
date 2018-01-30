package org.ligoj.app.plugin.prov.azure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.transaction.Transactional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractAppTest;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStorageLatency;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link ProvAzurePluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAzurePluginResourceTest extends AbstractAppTest {

	@Autowired
	private ProvAzurePluginResource resource;

	@Autowired
	private ProvResource provResource;

	private int subscription;

	@BeforeEach
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, Subscription.class, ProvLocation.class, ProvQuote.class, ProvStorageType.class,
						ProvStoragePrice.class, ProvInstancePriceTerm.class, ProvInstanceType.class, ProvInstancePrice.class,
						ProvQuoteInstance.class, ProvQuoteStorage.class },
				StandardCharsets.UTF_8.name());
		subscription = getSubscription("gStack", ProvAzurePluginResource.KEY);
	}

	@Test
	public void getConfiguration() {
		final QuoteVo vo = provResource.getConfiguration(subscription);
		Assertions.assertEquals("quote1", vo.getName());
		Assertions.assertNotNull(vo.getId());
		Assertions.assertNotNull(vo.getCreatedBy());
		Assertions.assertNotNull(vo.getCreatedDate());
		Assertions.assertNotNull(vo.getLastModifiedBy());
		Assertions.assertNotNull(vo.getLastModifiedDate());

		// Check compute
		final List<ProvQuoteInstance> instances = vo.getInstances();
		Assertions.assertEquals(3, instances.size());
		final ProvQuoteInstance quoteInstance = instances.get(0);
		Assertions.assertNotNull(quoteInstance.getId());
		Assertions.assertEquals("Standard-2.343-D15 v2-LINUX", quoteInstance.getName());
		final ProvInstancePrice instancePrice = quoteInstance.getPrice();
		Assertions.assertEquals(2.343, instancePrice.getCost(), 0.001);
		Assertions.assertEquals(VmOs.LINUX, instancePrice.getOs());
		Assertions.assertNotNull(instancePrice.getTerm().getId());
		Assertions.assertEquals(1, instancePrice.getTerm().getPeriod().intValue());
		Assertions.assertEquals("Standard", instancePrice.getTerm().getName());
		final ProvInstanceType instance = instancePrice.getType();
		Assertions.assertNotNull(instance.getId().intValue());
		Assertions.assertEquals("D15 v2", instance.getName());
		Assertions.assertEquals(20, instance.getCpu().intValue());
		Assertions.assertEquals(143360, instance.getRam().intValue());
		Assertions.assertTrue(instance.getConstant());

		// Check storage
		final List<ProvQuoteStorage> storages = vo.getStorages();
		Assertions.assertEquals(4, storages.size());
		final ProvQuoteStorage quoteStorage = storages.get(0);
		Assertions.assertNotNull(quoteStorage.getId());
		Assertions.assertEquals("server1-root", quoteStorage.getName());
		Assertions.assertEquals(20, quoteStorage.getSize().intValue());
		Assertions.assertNotNull(quoteStorage.getQuoteInstance());
		final ProvStoragePrice storage = quoteStorage.getPrice();
		final ProvStorageType type = storage.getType();
		Assertions.assertNotNull(storage.getId());
		Assertions.assertEquals(0, storage.getCostGb(), 0.001);
		Assertions.assertEquals(19.71, storage.getCost(), 0.001);
		Assertions.assertEquals(0, storage.getCostTransaction(), 0.001);
		Assertions.assertEquals("P10", type.getName());
		Assertions.assertEquals(ProvStorageLatency.LOWEST, type.getLatency());

		// Not attached storage
		Assertions.assertNull(storages.get(3).getQuoteInstance());

		// Transactional costs
		Assertions.assertEquals(0.00000072, storages.get(3).getPrice().getCostTransaction(), 0.001);
	}

	@Test
	public void getKey() {
		Assertions.assertEquals("service:prov:azure", resource.getKey());
	}

}
