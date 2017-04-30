package org.ligoj.app.plugin.prov.azure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.transaction.Transactional;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ligoj.app.AbstractAppTest;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteStorageVo;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.ProvInstance;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStorage;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.plugin.prov.model.VmStorageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Test class of {@link ProvAzureResource}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAzureResourceTest extends AbstractAppTest {

	@Autowired
	private ProvAzureResource resource;

	@Autowired
	private ProvResource provResource;

	private int subscription;

	@Before
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, Subscription.class, ProvQuote.class, ProvStorage.class,
						ProvInstancePriceType.class, ProvInstance.class, ProvInstancePrice.class,
						ProvQuoteInstance.class, ProvQuoteStorage.class },
				StandardCharsets.UTF_8.name());
		subscription = getSubscription("gStack", ProvAzureResource.SERVICE_KEY);
	}

	@Test
	public void getConfiguration() {
		final QuoteVo vo = provResource.getConfiguration(subscription);
		Assert.assertEquals("quote1", vo.getName());
		Assert.assertNotNull(vo.getId());
		Assert.assertNotNull(vo.getCreatedBy());
		Assert.assertNotNull(vo.getCreatedDate());
		Assert.assertNotNull(vo.getLastModifiedBy());
		Assert.assertNotNull(vo.getLastModifiedDate());

		// Check compute
		final List<ProvQuoteInstance> instances = vo.getInstances();
		Assert.assertEquals(3, instances.size());
		final ProvQuoteInstance quoteInstance = instances.get(0);
		Assert.assertNotNull(quoteInstance.getId());
		Assert.assertEquals("Standard-2.343-D15 v2-LINUX", quoteInstance.getName());
		final ProvInstancePrice instancePrice = quoteInstance.getInstancePrice();
		Assert.assertEquals(2.343, instancePrice.getCost(), 0.001);
		Assert.assertEquals(VmOs.LINUX, instancePrice.getOs());
		Assert.assertNotNull(instancePrice.getType().getId());
		Assert.assertEquals(1, instancePrice.getType().getPeriod().intValue());
		Assert.assertEquals("Standard", instancePrice.getType().getName());
		final ProvInstance instance = instancePrice.getInstance();
		Assert.assertNotNull(instance.getId().intValue());
		Assert.assertEquals("D15 v2", instance.getName());
		Assert.assertEquals(20, instance.getCpu().intValue());
		Assert.assertEquals(143360, instance.getRam().intValue());
		Assert.assertTrue(instance.getConstant());

		// Check storage
		final List<QuoteStorageVo> storages = vo.getStorages();
		Assert.assertEquals(4, storages.size());
		final QuoteStorageVo quoteStorage = storages.get(0);
		Assert.assertNotNull(quoteStorage.getId());
		Assert.assertEquals("server1-root", quoteStorage.getName());
		Assert.assertEquals(20, quoteStorage.getSize());
		Assert.assertNotNull(quoteStorage.getQuoteInstance());
		final ProvStorage storage = quoteStorage.getStorage();
		Assert.assertNotNull(storage.getId());
		Assert.assertEquals(0.169375, storage.getCost(), 0.001);
		Assert.assertEquals(0, storage.getTransactionalCost(), 0.001);
		Assert.assertEquals("P10", storage.getName());
		Assert.assertEquals(VmStorageType.HOT, storage.getType());

		//

		// Not attached storage
		Assert.assertNull(storages.get(3).getQuoteInstance());

		// Transactional costs
		Assert.assertEquals(0.00000072, storages.get(3).getStorage().getTransactionalCost(), 0.001);
	}

	@Test
	public void getKey() {
		Assert.assertEquals("service:prov:azure", resource.getKey());
	}

}
