/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceQuery.builder;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import javax.annotation.PostConstruct;
import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
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
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.UpdatedCost;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.azure.catalog.database.AzurePriceImportDatabase;
import org.ligoj.app.plugin.prov.azure.catalog.disk.AzurePriceImportDisk;
import org.ligoj.app.plugin.prov.azure.catalog.support.AzurePriceImportSupport;
import org.ligoj.app.plugin.prov.azure.catalog.vm.AzurePriceImportVm;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.catalog.ImportCatalogResource;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteInstanceRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteRepository;
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
import org.ligoj.app.plugin.prov.model.SupportType;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.plugin.prov.quote.database.ProvQuoteDatabaseResource;
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseLookup;
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseQuery;
import org.ligoj.app.plugin.prov.quote.instance.ProvQuoteInstanceResource;
import org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceLookup;
import org.ligoj.app.plugin.prov.quote.storage.ProvQuoteStorageResource;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageEditionVo;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageLookup;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageQuery;
import org.ligoj.app.plugin.prov.quote.support.ProvQuoteSupportResource;
import org.ligoj.app.plugin.prov.quote.support.QuoteSupportLookup;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link AzurePriceImport}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAzurePriceImportTest extends AbstractServerTest {

	private static final double DELTA = 0.001;

	private AzurePriceImport resource;

	@Autowired
	private ProvResource provResource;

	@Autowired
	private ProvQuoteInstanceResource qiResource;

	@Autowired
	private ProvQuoteDatabaseResource qbResource;

	@Autowired
	private ProvQuoteStorageResource qsResource;

	@Autowired
	private ProvQuoteSupportResource qs2Resource;

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

	@Autowired
	private ProvQuoteInstanceRepository qiRepository;

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

		// Mock catalog import helper
		final ImportCatalogResource helper = new ImportCatalogResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(helper);
		this.resource = initCatalog(helper, new AzurePriceImport());
		this.resource.setBase(initCatalog(helper, new AzurePriceImportBase()));
		this.resource.setDatabase(initCatalog(helper, new AzurePriceImportDatabase()));
		this.resource.setVm(initCatalog(helper, new AzurePriceImportVm()));
		this.resource.setDisk(initCatalog(helper, new AzurePriceImportDisk()));
		this.resource.setSupport(initCatalog(helper, new AzurePriceImportSupport()));

		clearAllCache();
		configuration.delete(AzurePriceImportBase.CONF_REGIONS);
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

	private <T extends AbstractImportCatalogResource> T initCatalog(ImportCatalogResource importHelper, T catalog) {
		applicationContext.getAutowireCapableBeanFactory().autowireBean(catalog);
		catalog.setImportCatalogResource(importHelper);
		MethodUtils.getMethodsListWithAnnotation(catalog.getClass(), PostConstruct.class).forEach(m -> {
			try {
				m.invoke(catalog);
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				// Ignore;
			}
		});
		return catalog;
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
		final ProvQuoteInstance instance = check(quote, 156.685d, 149.869d);

		// Check the 3 years term
		final QuoteInstanceLookup lookup = qiResource.lookup(instance.getConfiguration().getSubscription().getId(),
				builder().cpu(7).ram(1741).constant(true).ephemeral(true).build());
		Assertions.assertEquals(149.869d, lookup.getCost(), DELTA);
		Assertions.assertEquals(149.869d, lookup.getPrice().getCost(), DELTA);
		Assertions.assertEquals("three-year", lookup.getPrice().getTerm().getName());
		Assertions.assertFalse(lookup.getPrice().getTerm().isEphemeral());
		Assertions.assertEquals("ds4v2", lookup.getPrice().getType().getName());
		Assertions.assertEquals(24, ipRepository.countBy("term.name", "three-year"));

		Assertions.assertEquals("europe-north", lookup.getPrice().getLocation().getName());
		Assertions.assertEquals("North Europe", lookup.getPrice().getLocation().getDescription());
		checkImportStatus();

		// Install again to check the update without change
		resetImportTask();
		resource.install();
		provResource.updateCost(subscription);
		check(provResource.getConfiguration(subscription), 156.685d, 149.869d);
		checkImportStatus();

		// Now, change a price within the remote catalog

		// Point to another catalog with different prices
		configuration.put(AzurePriceImportVm.CONF_API_PRICES, "http://localhost:" + MOCK_PORT + "/v2");

		// Install the new catalog, update occurs
		resetImportTask();
		resource.install();
		provResource.updateCost(subscription);

		// Check the new price
		final QuoteVo newQuote = provResource.getConfiguration(subscription);
		Assertions.assertEquals(171.386d, newQuote.getCost().getMin(), DELTA);

		// Storage price is updated
		final ProvQuoteStorage storage = newQuote.getStorages().get(0);
		Assertions.assertEquals(1.537d, storage.getCost(), DELTA);
		Assertions.assertEquals(5, storage.getSize(), DELTA);

		// Compute price is updated
		final ProvQuoteInstance instance2 = newQuote.getInstances().get(0);
		Assertions.assertEquals(164.469d, instance2.getCost(), DELTA);
		ProvInstancePrice price = instance2.getPrice();
		Assertions.assertNull(price.getInitialCost());
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(164.469d, price.getCost(), DELTA);
		final ProvInstancePriceTerm priceType = price.getTerm();
		Assertions.assertEquals("three-year", priceType.getName());
		Assertions.assertFalse(priceType.isEphemeral());
		Assertions.assertEquals(36, priceType.getPeriod());

		ProvInstanceType type = price.getType();
		Assertions.assertEquals("ds4v2", type.getName());
		Assertions.assertEquals("{\"series\":\"Dsv2\",\"disk\":56}", type.getDescription());

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
		Assertions.assertEquals("{\"series\":\"F\",\"disk\":16}", type.getDescription());

		// Check rating of "ds15v2" (dedicated)
		price = iptRepository.findBy("type.name", "ds15v2");
		Assertions.assertEquals(ProvTenancy.DEDICATED, price.getTenancy());

		// Check status
		checkImportStatus();

		// Check some prices
		final ProvInstancePrice price2 = ipRepository.findBy("code", "europe-west/lowpriority/windows-a1-lowpriority");
		final ProvInstancePriceTerm term = price2.getTerm();
		Assertions.assertEquals("lowpriority", term.getName());
		Assertions.assertEquals(0, term.getPeriod());
		Assertions.assertEquals("europe-west", price2.getLocation().getName());
		Assertions.assertEquals(VmOs.WINDOWS, price2.getOs());
		Assertions.assertTrue(term.isEphemeral());

		// Lookup software
		final QuoteInstanceLookup lookupS = qiResource.lookup(subscription,

				builder().ram(1741).constant(true).ephemeral(true).os(VmOs.RHEL).software("SQL ENTERPRISE").build());
		Assertions.assertEquals("payg", lookupS.getPrice().getTerm().getName());
		Assertions.assertEquals("SQL ENTERPRISE", lookupS.getPrice().getSoftware());
		Assertions.assertNull(lookupS.getPrice().getLicense());
		Assertions.assertEquals("europe-north/payg/sql-redhat-enterprise-ds1v2-standard", lookupS.getPrice().getCode());

		// Lookup BYOL license
		final QuoteInstanceLookup lookupL = qiResource.lookup(subscription, builder().ram(1741).constant(true)
				.ephemeral(true).os(VmOs.WINDOWS).license("BYOL").software("SQL STANDARD").build());
		Assertions.assertEquals("payg", lookupL.getPrice().getTerm().getName());
		Assertions.assertEquals("SQL STANDARD", lookupL.getPrice().getSoftware());
		Assertions.assertEquals("BYOL", lookupL.getPrice().getLicense());
		final QuoteInstanceLookup lookupL1 = qiResource.lookup(subscription, builder().ram(1741).constant(true)
				.ephemeral(true).os(VmOs.WINDOWS).type("ds13-2-v2").license("BYOL").software("SQL ENTERPRISE").build());
		Assertions.assertEquals("three-year", lookupL1.getPrice().getTerm().getName());
		Assertions.assertEquals("SQL ENTERPRISE", lookupL1.getPrice().getSoftware());
		Assertions.assertEquals("BYOL", lookupL1.getPrice().getLicense());
		Assertions.assertEquals("europe-north/byol/three-year/sql-enterprise-ds13-2-v2-standard",
				lookupL1.getPrice().getCode());
		final QuoteInstanceLookup lookupL2 = qiResource.lookup(subscription,
				builder().ram(1741).constant(true).ephemeral(true).os(VmOs.WINDOWS).license("BYOL").build());
		Assertions.assertEquals("three-year", lookupL2.getPrice().getTerm().getName());
		Assertions.assertNull(lookupL2.getPrice().getSoftware());
		Assertions.assertEquals("BYOL", lookupL2.getPrice().getLicense());
		Assertions.assertEquals("europe-north/byol/three-year/windows-b1ms-standard", lookupL2.getPrice().getCode());

		// Check the support
		final QuoteSupportLookup lookupSu = qs2Resource
				.lookup(subscription, 1, SupportType.ALL, SupportType.ALL, SupportType.ALL, SupportType.ALL, Rate.BEST)
				.get(0);
		Assertions.assertEquals("Premier", lookupSu.getPrice().getType().getName());

		// Check the database
		final QuoteDatabaseLookup lookupB = qbResource.lookup(subscription,
				QuoteDatabaseQuery.builder().cpu(1).engine("MYSQL").build());
		Assertions.assertEquals("MYSQL", lookupB.getPrice().getEngine());
		Assertions.assertNull(lookupB.getPrice().getEdition());
		Assertions.assertEquals("payg", lookupB.getPrice().getTerm().getName());
		Assertions.assertEquals("basic-gen4-1", lookupB.getPrice().getType().getName());
		Assertions.assertEquals("europe-north/MYSQL/basic-compute-g4-1", lookupB.getPrice().getCode());
		Assertions.assertEquals(26.572, lookupB.getCost(), DELTA);
		Assertions.assertEquals(2048, lookupB.getPrice().getType().getRam().intValue());
		Assertions.assertEquals(1, lookupB.getPrice().getType().getCpu().intValue());
		Assertions.assertNull(lookupB.getPrice().getStorageEngine());

		final QuoteDatabaseLookup lookupB2 = qbResource.lookup(subscription,
				QuoteDatabaseQuery.builder().cpu(5).engine("SQL SERVER").build());
		Assertions.assertEquals("SQL SERVER", lookupB2.getPrice().getEngine());
		Assertions.assertEquals("ENTERPRISE", lookupB2.getPrice().getEdition());
		Assertions.assertEquals("payg", lookupB2.getPrice().getTerm().getName());
		Assertions.assertEquals("sql-bc-gen4-8", lookupB2.getPrice().getType().getName());
		Assertions.assertEquals("europe-north/SQL SERVER/managed-vcore-business-critical-gen4-8-per-hour", lookupB2.getPrice().getCode());
		Assertions.assertEquals(4003.46, lookupB2.getCost(), DELTA);
		Assertions.assertEquals(7168, lookupB2.getPrice().getType().getRam().intValue());
		Assertions.assertEquals(8, lookupB2.getPrice().getType().getCpu().intValue());
		Assertions.assertEquals("SQL SERVER", lookupB2.getPrice().getStorageEngine());

	}

	private void checkImportStatus() {
		final ImportCatalogStatus status = this.resource.getImportCatalogResource().getTask("service:prov:azure");
		Assertions.assertEquals(44, status.getDone());
		Assertions.assertEquals(44, status.getWorkload());
		Assertions.assertEquals("support", status.getPhase());
		Assertions.assertEquals(DEFAULT_USER, status.getAuthor());
		Assertions.assertTrue(status.getNbInstancePrices().intValue() >= 46);
		Assertions.assertEquals(45, status.getNbInstanceTypes().intValue()); // 34 VM + 11 DB
		Assertions.assertEquals(2, status.getNbLocations().intValue());
		Assertions.assertEquals(13, status.getNbStorageTypes().intValue()); // 5 + 8 DB
	}

	private void mockServer() throws IOException {
		patchConfigurationUrl();
		mockResource("/managed-disks/calculator/", "managed-disk");
		mockResource("/virtual-machines-base/calculator/", "base");
		mockResource("/virtual-machines-base-one-year/calculator/", "base-one-year");
		mockResource("/virtual-machines-base-three-year/calculator/", "base-three-year");

		// Database part
		mockResource("/mysql/calculator/", "mysql");
		mockResource("/mariadb/calculator/", "mariadb");
		mockResource("/postgresql/calculator/", "postgresql");
		mockResource("/sql-database/calculator/", "sql-database");

		// Software part
		mockResource("/virtual-machines-software/calculator/", "software");
		mockResource("/virtual-machines-software-one-year/calculator/", "software-one-year");
		mockResource("/virtual-machines-software-three-year/calculator/", "software-three-year");

		// BYOL part
		mockResource("/virtual-machines-ahb/calculator/", "ahb");
		mockResource("/virtual-machines-ahb-one-year/calculator/", "ahb-one-year");
		mockResource("/virtual-machines-ahb-three-year/calculator/", "ahb-three-year");

		// Another catalog version
		mockResource("/v2/managed-disks/calculator/", "v2/managed-disk");
		mockResource("/v2/virtual-machines-base/calculator/", "v2/base");
		mockResource("/v2/virtual-machines-base-one-year/calculator/", "v2/base-one-year");
		mockResource("/v2/virtual-machines-base-three-year/calculator/", "v2/base-three-year");
		mockResource("/v2/virtual-machines-software/calculator/", "v2/software");
		mockResource("/v2/virtual-machines-software-one-year/calculator/", "v2/software-one-year");
		mockResource("/v2/virtual-machines-software-three-year/calculator/", "v2/software-three-year");
		mockResource("/v2/virtual-machines-ahb/calculator/", "v2/ahb");
		mockResource("/v2/virtual-machines-ahb-one-year/calculator/", "v2/ahb-one-year");
		mockResource("/v2/virtual-machines-ahb-three-year/calculator/", "v2/ahb-three-year");
		mockResource("/v2/mysql/calculator/", "v2/mysql");
		mockResource("/v2/mariadb/calculator/", "v2/mariadb");
		mockResource("/v2/postgresql/calculator/", "v2/postgresql");
		mockResource("/v2/sql-database/calculator/", "v2/sql-database");
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
		Assertions.assertEquals(149.869d, price.getCost(), DELTA);
		Assertions.assertEquals(0.2053, price.getCostPeriod(), DELTA);
		final ProvInstancePriceTerm priceType = price.getTerm();
		Assertions.assertEquals("three-year", priceType.getName());
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
		Assertions.assertEquals(32, type.getIops());
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
		configuration.delete(AbstractAzureImport.CONF_API_PRICES);
		configuration.put(AzurePriceImportBase.CONF_REGIONS, "europe-north");
		configuration.put(AzurePriceImportVm.CONF_ITYPE, "ds4.*");
		configuration.put(AzurePriceImportDatabase.CONF_DTYPE, "(sql-bc-gen5-16|gp-gen5-.*)");
		configuration.put(AzurePriceImportVm.CONF_OS, "(WINDOWS|LINUX)");

		// Check the reserved
		final QuoteVo quote = installAndConfigure();
		Assertions.assertTrue(quote.getCost().getMin() > 150);

		// Check the spot
		final QuoteInstanceLookup lookup = qiResource.lookup(subscription,
				builder().cpu(8).ram(26000).constant(true).type("ds4v2").usage("36month").build());

		Assertions.assertTrue(lookup.getCost() > 100d);
		final ProvInstancePrice instance2 = lookup.getPrice();
		Assertions.assertEquals("three-year", instance2.getTerm().getName());
		Assertions.assertEquals("ds4v2", instance2.getType().getName());
	}

	private void patchConfigurationUrl() {
		configuration.put(AbstractAzureImport.CONF_API_PRICES, "http://localhost:" + MOCK_PORT);
	}

	private int server1() {
		return qiRepository.findByName("server1").getId();
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
		final QuoteInstanceLookup lookup = qiResource.lookup(subscription,
				builder().cpu(8).ram(26000).constant(true).type("ds4v2").usage("36month").build());

		final QuoteInstanceEditionVo ivo = new QuoteInstanceEditionVo();
		ivo.setCpu(1d);
		ivo.setRam(1);
		ivo.setPrice(lookup.getPrice().getId());
		ivo.setName("server1");
		ivo.setSubscription(subscription);
		final UpdatedCost createInstance = qiResource.create(ivo);
		Assertions.assertTrue(createInstance.getTotal().getMin() > 1);
		Assertions.assertTrue(createInstance.getId() > 0);
		em.flush();
		em.clear();

		// Lookup & add STANDARD storage to this instance
		// ---------------------------------
		QuoteStorageLookup slookup = qsResource
				.lookup(subscription, QuoteStorageQuery.builder().size(5).latency(Rate.LOW).instance(server1()).build())
				.get(0);
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
		svo.setQuoteInstance(server1());
		svo.setSize(5);
		svo.setType(type.getName());
		svo.setName("sda1");
		svo.setSubscription(subscription);
		UpdatedCost newStorage = qsResource.create(svo);
		Assertions.assertTrue(newStorage.getTotal().getMin() > 100);
		Assertions.assertEquals(1.536, newStorage.getCost().getMin(), DELTA);

		// Lookup & add PREMIUM storage to this quote
		// ---------------------------------
		slookup = qsResource
				.lookup(subscription,
						QuoteStorageQuery.builder().instance(server1()).latency(Rate.LOW).optimized(ProvStorageOptimized.IOPS).build())
				.get(0);
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
		svo.setQuoteInstance(server1());
		newStorage = qsResource.create(svo);
		Assertions.assertTrue(newStorage.getTotal().getMin() > 100);
		Assertions.assertEquals(5.28, newStorage.getCost().getMin(), DELTA);

		return provResource.getConfiguration(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, ProvAzurePluginResource.KEY);
	}
}
