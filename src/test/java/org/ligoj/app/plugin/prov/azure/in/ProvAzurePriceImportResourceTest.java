/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.in;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.iam.model.CacheCompany;
import org.ligoj.app.iam.model.CacheUser;
import org.ligoj.app.model.DelegateNode;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.ProvQuoteInstanceResource;
import org.ligoj.app.plugin.prov.ProvQuoteStorageResource;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.QuoteInstanceLookup;
import org.ligoj.app.plugin.prov.QuoteStorageEditionVo;
import org.ligoj.app.plugin.prov.QuoteStorageLoopup;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.UpdatedCost;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteRepository;
import org.ligoj.app.plugin.prov.in.ImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.ProvUsage;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link ProvAzurePriceImportResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAzurePriceImportResourceTest extends AbstractServerTest {

	private static final double DELTA = 0.001;

	private ProvAzurePriceImportResource resource;

	@Autowired
	private ProvResource provResource;

	@Autowired
	private ProvQuoteInstanceResource qiResource;

	@Autowired
	private ProvQuoteStorageResource qsResource;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Autowired
	private ProvInstancePriceRepository iptRepository;

	@Autowired
	private ProvInstanceTypeRepository itRepository;

	@Autowired
	private ProvQuoteRepository repository;

	@Autowired
	private ConfigurationResource configuration;

	protected int subscription;

	@BeforeEach
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class,
						Parameter.class, ProvLocation.class, Subscription.class, ParameterValue.class,
						ProvQuote.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Disable inner transaction
		this.resource = new ProvAzurePriceImportResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		this.resource.initRate();
		this.resource.initVmTenancy();
		this.resource.initRegion();
		this.resource.setImportCatalogResource(new ImportCatalogResource());
		applicationContext.getAutowireCapableBeanFactory().autowireBean(this.resource.getImportCatalogResource());
		configuration.delete(ProvAzurePriceImportResource.CONF_REGIONS);
		initSpringSecurityContext(DEFAULT_USER);
		resetImportTask();

		final ProvUsage usage = new ProvUsage();
		usage.setName("36month");
		usage.setRate(100);
		usage.setDuration(36);
		usage.setConfiguration(repository.findBy("subscription.id", subscription));
		em.persist(usage);
		em.flush();
		em.clear();
	}

	private void resetImportTask() {
		this.resource.getImportCatalogResource().endTask("service:prov:azure", false);
		this.resource.getImportCatalogResource().startTask("service:prov:azure", t -> {
			t.setLocation(null);
			t.setNbInstancePrices(null);
			t.setNbInstanceTypes(null);
			t.setNbStorageTypes(null);
			t.setWorkload(0);
			t.setDone(0);
			t.setPhase(null);
		});
	}

	@Test
	public void installOffLine() throws Exception {
		// Install a new configuration
		final QuoteVo quote = install();

		// Check the whole quote
		final ProvQuoteInstance instance = check(quote, 157.096, 150.28d);

		// Check the spot
		final QuoteInstanceLookup lookup = qiResource.lookup(instance.getConfiguration().getSubscription().getId(), 2,
				1741, true, VmOs.LINUX, null, true, null, null);
		Assertions.assertEquals(150.28, lookup.getCost(), DELTA);
		Assertions.assertEquals(150.28, lookup.getPrice().getCost(), DELTA);
		Assertions.assertEquals("base-three-year", lookup.getPrice().getTerm().getName());
		Assertions.assertFalse(lookup.getPrice().getTerm().isEphemeral());
		Assertions.assertEquals("ds4v2", lookup.getPrice().getType().getName());
		Assertions.assertEquals(10, ipRepository.countBy("term.name", "base-three-year"));

		Assertions.assertEquals("europe-north", lookup.getPrice().getLocation().getName());
		Assertions.assertEquals("North Europe", lookup.getPrice().getLocation().getDescription());
		checkImportStatus();

		// Install again to check the update without change
		resetImportTask();
		resource.install();
		provResource.updateCost(subscription);
		check(provResource.getConfiguration(subscription), 157.096d, 150.28d);
		checkImportStatus();

		// Now, change a price within the remote catalog

		// Point to another catalog with different prices
		configuration.saveOrUpdate(ProvAzurePriceImportResource.CONF_API_PRICES,
				"http://localhost:" + MOCK_PORT + "/v2");

		// Install the new catalog, update occurs
		resetImportTask();
		resource.install();
		provResource.updateCost(subscription);

		// Check the new price
		final QuoteVo newQuote = provResource.getConfiguration(subscription);
		Assertions.assertEquals(171.837d, newQuote.getCost().getMin(), DELTA);

		// Storage price is updated
		final ProvQuoteStorage storage = newQuote.getStorages().get(0);
		Assertions.assertEquals(1.537d, storage.getCost(), DELTA);
		Assertions.assertEquals(5, storage.getSize(), DELTA);

		// Compute price is updated
		final ProvQuoteInstance instance2 = newQuote.getInstances().get(0);
		Assertions.assertEquals(164.92d, instance2.getCost(), DELTA);
		ProvInstancePrice price = instance2.getPrice();
		Assertions.assertNull(price.getInitialCost());
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(164.92d, price.getCost(), DELTA);
		final ProvInstancePriceTerm priceType = price.getTerm();
		Assertions.assertEquals("base-three-year", priceType.getName());
		Assertions.assertFalse(priceType.isEphemeral());
		Assertions.assertEquals(36, priceType.getPeriod());

		ProvInstanceType type = price.getType();
		Assertions.assertEquals("ds4v2", type.getName());
		Assertions.assertEquals("series:Dsv2, disk:56GiB", type.getDescription());

		// Check rating of "ds4v2"
		Assertions.assertEquals(Rate.GOOD, type.getRamRate());
		Assertions.assertEquals(Rate.GOOD, type.getCpuRate());
		Assertions.assertEquals(Rate.MEDIUM, type.getNetworkRate());
		Assertions.assertEquals(Rate.GOOD, type.getStorageRate());

		// Check rating of "f1"
		type = itRepository.findByName("f1");
		Assertions.assertEquals(Rate.GOOD, type.getRamRate());
		Assertions.assertEquals(Rate.MEDIUM, type.getCpuRate());
		Assertions.assertEquals(Rate.MEDIUM, type.getNetworkRate());
		Assertions.assertEquals(Rate.GOOD, type.getStorageRate());
		Assertions.assertEquals("series:F, disk:16GiB", type.getDescription());

		// Check rating of "ds15v2" (dedicated)
		price = iptRepository.findBy("type.name", "ds15v2");
		Assertions.assertEquals(ProvTenancy.DEDICATED, price.getTenancy());

		// Check status
		checkImportStatus();

		// Check some prices
		final ProvInstancePrice price2 = ipRepository.findBy("code", "europe-west-lowpriority-windows-a1-lowpriority");
		final ProvInstancePriceTerm term = price2.getTerm();
		Assertions.assertEquals("lowpriority", term.getName());
		Assertions.assertEquals(0, term.getPeriod());
		Assertions.assertEquals("europe-west", price2.getLocation().getName());
		Assertions.assertEquals(VmOs.WINDOWS, price2.getOs());
		Assertions.assertTrue(term.isEphemeral());
	}

	private void checkImportStatus() {
		final ImportCatalogStatus status = this.resource.getImportCatalogResource().getTask("service:prov:azure");
		Assertions.assertEquals(14, status.getDone());
		Assertions.assertEquals(14, status.getWorkload());
		Assertions.assertEquals("finalize", status.getPhase());
		Assertions.assertEquals(DEFAULT_USER, status.getAuthor());
		Assertions.assertTrue(status.getNbInstancePrices().intValue() >= 46);
		Assertions.assertEquals(5, status.getNbInstanceTypes().intValue());
		Assertions.assertEquals(2, status.getNbLocations().intValue());
		Assertions.assertEquals(5, status.getNbStorageTypes().intValue());
	}

	private void mockServer() throws IOException {
		patchConfigurationUrl();
		mockResource("/managed-disks/calculator/", "managed-disk");
		mockResource("/virtual-machines-base/calculator/", "base");
		mockResource("/virtual-machines-base-one-year/calculator/", "base-one-year");
		mockResource("/virtual-machines-base-three-year/calculator/", "base-three-year");
		// Another catalog version
		mockResource("/v2/managed-disks/calculator/", "v2/managed-disk");
		mockResource("/v2/virtual-machines-base/calculator/", "v2/base");
		mockResource("/v2/virtual-machines-base-one-year/calculator/", "v2/base-one-year");
		mockResource("/v2/virtual-machines-base-three-year/calculator/", "v2/base-three-year");
		httpServer.start();
	}

	private void mockResource(final String path, final String json) throws IOException {
		httpServer.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
				.toString(new ClassPathResource("mock-server/azure/" + json + ".json").getInputStream(), "UTF-8"))));
	}

	private ProvQuoteInstance check(final QuoteVo quote, final double cost, final double computeCost) {
		Assertions.assertEquals(cost, quote.getCost().getMin(), DELTA);
		checkStorage(quote.getStorages().get(0));
		return checkInstance(quote.getInstances().get(0), computeCost);
	}

	private ProvQuoteInstance checkInstance(final ProvQuoteInstance instance, final double cost) {
		Assertions.assertEquals(cost, instance.getCost(), DELTA);
		final ProvInstancePrice price = instance.getPrice();
		Assertions.assertNull(price.getInitialCost());
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(150.28, price.getCost(), DELTA);
		Assertions.assertEquals(0.2053, price.getCostPeriod(), DELTA);
		final ProvInstancePriceTerm priceType = price.getTerm();
		Assertions.assertEquals("base-three-year", priceType.getName());
		Assertions.assertFalse(priceType.isEphemeral());
		Assertions.assertEquals(36, priceType.getPeriod());
		Assertions.assertEquals("ds4v2", price.getType().getName());
		return instance;
	}

	private ProvQuoteStorage checkStorage(final ProvQuoteStorage storage) {
		Assertions.assertEquals(1.536d, storage.getCost(), DELTA);
		Assertions.assertEquals(5, storage.getSize(), DELTA);
		Assertions.assertNotNull(storage.getQuoteInstance());
		final ProvStorageType type = storage.getPrice().getType();
		Assertions.assertEquals("s4", type.getName());
		Assertions.assertEquals(500, type.getIops());
		Assertions.assertEquals(60, type.getThroughput());
		Assertions.assertEquals(Rate.MEDIUM, type.getLatency());
		Assertions.assertEquals(0.05, storage.getPrice().getCostTransaction(), DELTA);
		Assertions.assertEquals(32, type.getMinimal());
		Assertions.assertEquals(32, type.getMaximal().intValue());
		return storage;
	}

	/**
	 * Common offline install and configuring an instance
	 * 
	 * @return The new quote from the installed
	 */
	private QuoteVo install() throws Exception {
		mockServer();

		// Check the basic quote
		return installAndConfigure();
	}

	@Test
	public void installOnLine() throws Exception {
		configuration.delete(ProvAzurePriceImportResource.CONF_API_PRICES);
		configuration.saveOrUpdate(ProvAzurePriceImportResource.CONF_REGIONS, "europe-north");

		// Check the reserved
		final QuoteVo quote = installAndConfigure();
		Assertions.assertTrue(quote.getCost().getMin() > 150);

		// Check the spot
		final QuoteInstanceLookup lookup = qiResource.lookup(subscription, 8, 26000, true, VmOs.LINUX, "ds4v2", false,
				null, "36month");

		Assertions.assertTrue(lookup.getCost() > 100d);
		final ProvInstancePrice instance2 = lookup.getPrice();
		Assertions.assertEquals("base-three-year", instance2.getTerm().getName());
		Assertions.assertEquals("ds4v2", instance2.getType().getName());
	}

	private void patchConfigurationUrl() {
		configuration.saveOrUpdate(ProvAzurePriceImportResource.CONF_API_PRICES, "http://localhost:" + MOCK_PORT);
	}

	/**
	 * Install and check
	 */
	private QuoteVo installAndConfigure() throws IOException, Exception {
		resource.install();
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// Request an instance that would not be a Spot
		final QuoteInstanceLookup lookup = qiResource.lookup(subscription, 8, 26000, true, VmOs.LINUX, "ds4v2", false,
				null, "36month");

		final QuoteInstanceEditionVo ivo = new QuoteInstanceEditionVo();
		ivo.setCpu(1d);
		ivo.setRam(1);
		ivo.setPrice(lookup.getPrice().getId());
		ivo.setName("server1");
		ivo.setSubscription(subscription);
		final UpdatedCost createInstance = qiResource.create(ivo);
		Assertions.assertTrue(createInstance.getTotalCost().getMin() > 1);
		final int instance = createInstance.getId();
		em.flush();
		em.clear();

		// Lookup & add STANDARD storage to this instance
		// ---------------------------------
		QuoteStorageLoopup slookup = qsResource.lookup(subscription, 5, Rate.LOW, instance, null, null).get(0);
		Assertions.assertEquals(1.536, slookup.getCost(), DELTA);

		// Check price & type
		ProvStoragePrice price = slookup.getPrice();
		ProvStorageType type = price.getType();
		Assertions.assertEquals("s4", type.getName());
		Assertions.assertEquals(Rate.MEDIUM, type.getLatency());
		Assertions.assertNull(type.getOptimized());
		Assertions.assertEquals("europe-north", price.getLocation().getName());
		Assertions.assertEquals("North Europe", price.getLocation().getDescription());

		QuoteStorageEditionVo svo = new QuoteStorageEditionVo();
		svo.setQuoteInstance(instance);
		svo.setSize(5);
		svo.setType(type.getName());
		svo.setName("sda1");
		svo.setSubscription(subscription);
		UpdatedCost newStorage = qsResource.create(svo);
		Assertions.assertTrue(newStorage.getTotalCost().getMin() > 100);
		Assertions.assertEquals(1.536, newStorage.getResourceCost().getMin(), DELTA);

		// Lookup & add PREMIUM storage to this quote
		// ---------------------------------
		slookup = qsResource.lookup(subscription, 1, Rate.LOW, null, ProvStorageOptimized.IOPS, null).get(0);
		Assertions.assertEquals(5.28, slookup.getCost(), DELTA);

		// Check price & type
		price = slookup.getPrice();
		type = price.getType();
		Assertions.assertEquals("p4", type.getName());
		Assertions.assertEquals(120, type.getIops());
		Assertions.assertEquals(25, type.getThroughput());
		Assertions.assertEquals(Rate.BEST, type.getLatency());
		Assertions.assertEquals(ProvStorageOptimized.IOPS, type.getOptimized());
		Assertions.assertEquals("europe-north", price.getLocation().getName());
		Assertions.assertEquals("North Europe", price.getLocation().getDescription());

		svo = new QuoteStorageEditionVo();
		svo.setOptimized(ProvStorageOptimized.IOPS);
		svo.setSize(1);
		svo.setType(type.getName());
		svo.setName("sda2");
		svo.setSubscription(subscription);
		newStorage = qsResource.create(svo);
		Assertions.assertTrue(newStorage.getTotalCost().getMin() > 100);
		Assertions.assertEquals(5.28, newStorage.getResourceCost().getMin(), DELTA);

		return provResource.getConfiguration(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, ProvAzurePluginResource.KEY);
	}
}
