/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.node2;

import com.icodici.crypto.EncryptionError;
import com.icodici.crypto.KeyAddress;
import com.icodici.crypto.PrivateKey;
import com.icodici.crypto.PublicKey;
import com.icodici.crypto.digest.Crc32;
import com.icodici.db.DbPool;
import com.icodici.db.PooledDb;
import com.icodici.universa.Approvable;
import com.icodici.universa.Core;
import com.icodici.universa.Decimal;
import com.icodici.universa.HashId;
import com.icodici.universa.contract.*;
import com.icodici.universa.contract.jsapi.JSApiAccessor;
import com.icodici.universa.contract.jsapi.JSApiContract;
import com.icodici.universa.contract.jsapi.JSApiScriptParameters;
import com.icodici.universa.contract.permissions.*;
import com.icodici.universa.contract.roles.ListRole;
import com.icodici.universa.contract.roles.Role;
import com.icodici.universa.contract.roles.RoleLink;
import com.icodici.universa.contract.roles.SimpleRole;
import com.icodici.universa.contract.services.*;
import com.icodici.universa.node.*;
import com.icodici.universa.node.network.TestKeys;
import com.icodici.universa.node2.network.*;
import net.sergeych.boss.Boss;
import net.sergeych.tools.Average;
import net.sergeych.tools.Binder;
import net.sergeych.tools.BufferedLogger;
import net.sergeych.tools.Do;
import net.sergeych.utils.Bytes;
import net.sergeych.utils.LogPrinter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.units.qual.A;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.spongycastle.util.encoders.Hex;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.junit.Assert.*;

@Ignore("start it manually")
public class MainTest {

    @Ignore
    @Test
    public void checkMemoryLeaks() throws Exception {

        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");
        List<Ledger> ledgers = new ArrayList<>();

        for (int it = 0; it < 100; it++) {
            if (it % 10 == 0)
                System.out.println("Iteration " + it);
            dbUrls.stream().forEach(url -> {
                try {
                    clearLedger(url);
                    PostgresLedger ledger = new PostgresLedger(url);
                    ledgers.add(ledger);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            TestSpace ts = prepareTestSpace();

            //ts.node.config.getKeysWhiteList().add(TestKeys.publicKey(3));
            ts.node.config.getAddressesWhiteList().add(new KeyAddress(TestKeys.publicKey(3), 0, true));
            Contract testContract = new Contract(ts.myKey);
            testContract.seal();
            assertTrue(testContract.isOk());
            Parcel parcel = createParcelWithFreshU(ts.client, testContract, Do.listOf(ts.myKey));
            ts.client.registerParcel(parcel.pack(), 18000);

            Contract testContract2 = new Contract(ts.myKey);
            testContract2.seal();
            assertTrue(testContract2.isOk());
            ts.client.register(testContract2.getPackedTransaction(), 18000);

            ts.nodes.forEach(x -> x.shutdown());

            ts.myKey = null;
            ts.nodes.clear();
            ts.node = null;
            ts.nodes = null;
            ts.client = null;
            ts = null;

            ledgers.stream().forEach(ledger -> ledger.close());
            ledgers.clear();
            System.gc();
            Thread.sleep(2000);
        }
    }

    @Ignore
    @Test
    public void checkPublicKeyMemoryLeak() throws Exception {

        byte[] bytes = Do.read("./src/test_contracts/keys/u_key.public.unikey");

        for (int it = 0; it < 10000; it++) {
            PublicKey pk = new PublicKey(bytes);
            pk = null;
            System.gc();
        }
    }
    @Before
    public void clearLedgers() throws Exception {
        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");
        List<Ledger> ledgers = new ArrayList<>();
        dbUrls.stream().forEach(url -> {
            try {
                clearLedger(url);
                PostgresLedger ledger = new PostgresLedger(url);
                ledgers.add(ledger);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        LogPrinter.showDebug(false);
    }

    @Test
    public void startNode() throws Exception {
        String path = new File("src/test_node_config_v2/node1").getAbsolutePath();
        System.out.println(path);
        String[] args = new String[]{"--test", "--config", path, "--nolog"};
        Main main = new Main(args);
        main.waitReady();
        BufferedLogger l = main.logger;

        Client client = new Client(
                "http://localhost:8080",
                TestKeys.privateKey(3),
                main.getNodePublicKey(),
                null
        );

        Binder data = client.command("status");
        data.getStringOrThrow("status");
//        assertThat(data.getListOrThrow("log").size(), greaterThan(3));
        BasicHttpClient.Answer a = client.request("ping");
        assertEquals("200: {ping=pong}", a.toString());

        Contract c = new Contract();
        c.setIssuerKeys(TestKeys.publicKey(3));
        c.addSignerKey(TestKeys.privateKey(3));
        c.registerRole(new RoleLink("owner", "issuer"));
        c.registerRole(new RoleLink("creator", "issuer"));
        c.setExpiresAt(ZonedDateTime.now().plusDays(2));
        byte[] sealed = c.seal();
//        Bytes.dump(sealed);

        Contract c1 = new Contract(sealed);
        assertArrayEquals(c.getLastSealedBinary(), c1.getLastSealedBinary());

        main.cache.put(c, null);
        assertNotNull(main.cache.get(c.getId()));

        URL url = new URL("http://localhost:8080/contracts/" + c.getId().toBase64String());
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        assertEquals(200, con.getResponseCode());
        byte[] data2 = Do.read(con.getInputStream());

        assertArrayEquals(c.getPackedTransaction(), data2);

        url = new URL("http://localhost:8080/network");
        con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        assertEquals(200, con.getResponseCode());
        Binder bres = Boss.unpack((Do.read(con.getInputStream())))
                .getBinderOrThrow("response");
        List<Binder> ni = bres.getBinders("nodes");
        String pubUrls = ni.stream().map(x -> x.getStringOrThrow("url"))
                .collect(Collectors.toList())
                .toString();

        assertEquals("[http://localhost:8080, http://localhost:6002, http://localhost:6004, http://localhost:6006]", pubUrls);

        main.shutdown();
        main.logger.stopInterceptingStdOut();
        main.logger.getCopy().forEach(x -> System.out.println(x));
    }

    Main createMain(String name, boolean nolog) throws InterruptedException {
        return createMain(name,"",nolog);
    }

    Main createMain(String name, String postfix, boolean nolog) throws InterruptedException {
        String path = new File("src/test_node_config_v2" + postfix + "/" + name).getAbsolutePath();
        System.out.println(path);
        String[] args = new String[]{"--test", "--config", path, nolog ? "--nolog" : ""};

        List<Main> mm = new ArrayList<>();

        Thread thread = new Thread(() -> {
            try {
                Main m = new Main(args);
                try {
                    m.config.addTransactionUnitsIssuerKeyData(new KeyAddress("Zau3tT8YtDkj3UDBSznrWHAjbhhU4SXsfQLWDFsv5vw24TLn6s"));
                } catch (KeyAddress.IllegalAddressException e) {
                    e.printStackTrace();
                }

                try {
                    //m.config.getKeysWhiteList().add(new PublicKey(Do.read("./src/test_contracts/keys/u_key.public.unikey")));
                    m.config.getAddressesWhiteList().add(new KeyAddress(new PublicKey(Do.read("./src/test_contracts/keys/u_key.public.unikey")), 0, true));
                } catch (IOException e) {
                    e.printStackTrace();
                }

                //m.config.getKeysWhiteList().add(m.config.getUIssuerKey());
                m.waitReady();
                mm.add(m);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        thread.setName("Node Server: " + name);
        thread.start();

        while (mm.size() == 0) {
            Thread.sleep(100);
        }
        return mm.get(0);
    }

    Main createMainFromDb(String dbUrl, boolean nolog) throws InterruptedException {
        String[] args = new String[]{"--test","--database", dbUrl, nolog ? "--nolog" : ""};

        List<Main> mm = new ArrayList<>();

        Thread thread = new Thread(() -> {
            try {
                Main m = new Main(args);
                try {
                    m.config.addTransactionUnitsIssuerKeyData(new KeyAddress("Zau3tT8YtDkj3UDBSznrWHAjbhhU4SXsfQLWDFsv5vw24TLn6s"));
                } catch (KeyAddress.IllegalAddressException e) {
                    e.printStackTrace();
                }

                try {
                    //m.config.getKeysWhiteList().add(new PublicKey(Do.read("./src/test_contracts/keys/u_key.public.unikey")));
                    m.config.getAddressesWhiteList().add(new KeyAddress(new PublicKey(Do.read("./src/test_contracts/keys/u_key.public.unikey")), 0, true));
                } catch (IOException e) {
                    e.printStackTrace();
                }

                //m.config.getKeysWhiteList().add(m.config.getUIssuerKey());
                m.waitReady();
                mm.add(m);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        thread.setName("Node Server: " + dbUrl);
        thread.start();

        while (mm.size() == 0) {
            Thread.sleep(100);
        }
        return mm.get(0);
    }

    @Test
    public void networkReconfigurationTestSerial() throws Exception {

        //create 4 nodes from config file. 3 know each other. 4th knows everyone. nobody knows 4th
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mm.add(createMain("node" + (i + 1),"_dynamic_test", false));
        }
        //shutdown nodes
        for(Main m : mm) {
            m.shutdown();
        }
        mm.clear();

        //initialize same nodes from db
        List<String> dbUrls = new ArrayList<>();
        Thread.sleep(1000);
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");

        for (int i = 0; i < 4; i++) {
            mm.add(createMainFromDb(dbUrls.get(i), false));
        }

        PrivateKey myKey = TestKeys.privateKey(3);
        Main main = mm.get(3);

        PrivateKey universaKey = new PrivateKey(Do.read("./src/test_contracts/keys/u_key.private.unikey"));
        Contract contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

        //registering with UNKNOWN node. Shouldn't succeed
        int attempts = 3;

        Client client = new Client(universaKey, main.myInfo, null);

        ItemResult rr = client.register(contract.getPackedTransaction(), 5000);
        while (attempts-- > 0) {
            rr = client.getState(contract.getId());
            System.out.println(rr);
            Thread.currentThread().sleep(1000);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.PENDING_POSITIVE);

        contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

        //registering with KNOWN node. Should succeed
        Client clientKnown = new Client(universaKey, mm.get(0).myInfo, null);
        clientKnown.register(contract.getPackedTransaction(), 15000);
        while (true) {
            rr = clientKnown.getState(contract.getId());
            Thread.currentThread().sleep(500);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.APPROVED);

        //Make 4th node KNOWN to other nodes
        for(int i = 0; i < 3; i++) {
            mm.get(i).node.addNode(main.myInfo);
        }

        contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

//        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.DETAILED);
//        mm.get(0).setUDPVerboseLevel(DatagramAdapter.VerboseLevel.DETAILED);

        client.register(contract.getPackedTransaction(), 15000);
        while (true) {
            rr = client.getState(contract.getId());
            Thread.currentThread().sleep(500);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.APPROVED);
//        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);
//        mm.get(0).setUDPVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);

        //Make 4th node UNKNOWN to other nodes
        for(int i = 0; i < 3; i++) {
            mm.get(i).node.removeNode(main.myInfo);
        }

        contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

        //registering with UNKNOWN node. Shouldn't succeed
        attempts = 3;
        rr = client.register(contract.getPackedTransaction(), 15000);
        while (attempts-- > 0) {
            rr = client.getState(contract.getId());
            Thread.currentThread().sleep(1000);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.PENDING_POSITIVE);

        contract = new Contract(universaKey);
        contract.seal();
        assertTrue(contract.isOk());

        //registering with KNOWN node. Should succeed
        clientKnown.register(contract.getPackedTransaction(), 15000);
        while (true) {
            rr = clientKnown.getState(contract.getId());
            Thread.currentThread().sleep(500);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state,ItemState.APPROVED);

        for(Main m : mm) {
            m.shutdown();
        }
    }

    @Test // no asserts
    public void networkReconfigurationTestParallel() throws Exception {
        //create 4 nodes from config file. 3 know each other. 4th knows everyone. nobody knows 4th
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mm.add(createMain("node" + (i + 1), "_dynamic_test", false));
        }
        //shutdown nodes
        for (Main m : mm) {
            m.shutdown();
        }
        mm.clear();

        //initialize same nodes from db
        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");

        Random rand = new Random();
        rand.setSeed(new Date().getTime());

        final ArrayList<Integer> clientSleeps = new ArrayList<>();
        final ArrayList<Integer> nodeSleeps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mm.add(createMainFromDb(dbUrls.get(i), false));
            nodeSleeps.add(rand.nextInt(100));
        }

        PrivateKey myKey = TestKeys.privateKey(3);

        final ArrayList<Client> clients = new ArrayList<>();
        final ArrayList<Integer> clientNodes = new ArrayList<>();
        final ArrayList<Contract> contracts = new ArrayList<>();
        final ArrayList<Parcel> parcels = new ArrayList<>();
        final ArrayList<Boolean> contractsApproved = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Contract contract = new Contract(myKey);
            contract.seal();
            assertTrue(contract.isOk());
            contracts.add(contract);
            contractsApproved.add(false);
            NodeInfo info = mm.get(rand.nextInt(3)).myInfo;
            clientNodes.add(info.getNumber());
            Client client = new Client(TestKeys.privateKey(i), info, null);
            clients.add(client);
            clientSleeps.add(rand.nextInt(100));
            Parcel parcel = createParcelWithFreshU(client,contract,Do.listOf(myKey));
            parcels.add(parcel);
        }

        Semaphore semaphore = new Semaphore(-39);
        final AtomicInteger atomicInteger = new AtomicInteger(40);
        for (int i = 0; i < 40; i++) {
            int finalI = i;
            Thread th = new Thread(() -> {
                try {
                    //Thread.sleep(clientSleeps.get(finalI));
                    Thread.sleep(clientSleeps.get(finalI));
                    Contract contract = contracts.get(finalI);
                    Client client = clients.get(finalI);
                    System.out.println("Register item " + contract.getId().toBase64String() + " @ node #" + clientNodes.get(finalI));
                    client.registerParcel(parcels.get(finalI).pack(), 15000);
                    ItemResult rr;
                    while (true) {
                        rr = client.getState(contract.getId());
                        Thread.currentThread().sleep(500);
                        if (!rr.state.isPending())
                            break;
                    }
                    assertEquals(rr.state, ItemState.APPROVED);
                    semaphore.release();
                    atomicInteger.decrementAndGet();
                    contractsApproved.set(finalI, true);
                } catch (ClientError clientError) {
                    clientError.printStackTrace();
                    fail(clientError.getMessage());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    fail(e.getMessage());
                } catch (IOException e) {
                    e.printStackTrace();
                    fail(e.getMessage());
                }
            });
            th.start();
        }

        for (int i = 0; i < 3; i++) {
            int finalI = i;
            Thread th = new Thread(() -> {
                try {
                    //Thread.sleep(nodeSleeps.get(finalI));
                    Thread.sleep(nodeSleeps.get(finalI)  );
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    fail(e.getMessage());
                }
                System.out.println("Adding new node @ node #" + (finalI + 1));
                mm.get(finalI).node.addNode(mm.get(3).myInfo);
                System.out.println("Done new node @ node #" + (finalI + 1));

            });
            th.start();
        }

        Thread.sleep(5000);

        if (!semaphore.tryAcquire(15, TimeUnit.SECONDS)) {
            for (int i = 0; i < contractsApproved.size(); i++) {
                if (!contractsApproved.get(i)) {
                    System.out.println("Stuck item:" + contracts.get(i).getId().toBase64String());
                }
            }

            System.out.print("Client sleeps: ");
            for (Integer s : clientSleeps) {
                System.out.print(s + ", ");
            }
            System.out.println();

            System.out.print("Node sleeps: ");
            for (Integer s : nodeSleeps) {
                System.out.print(s + ", ");
            }
            System.out.println();

            fail("Items stuck: " + atomicInteger.get());
        }

        for (Main m : mm) {
            m.shutdown();
        }
        System.gc();
    }

    @Test
    public void reconfigurationContractTest() throws Exception {
        PrivateKey issuerKey = new PrivateKey(Do.read("./src/test_contracts/keys/reconfig_key.private.unikey"));

        List<Main> mm = new ArrayList<>();
        List<PrivateKey> nodeKeys = new ArrayList<>();
        List<PrivateKey> nodeKeysNew = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            mm.add(createMain("node" + (i + 1), "_dynamic_test", false));
            if(i < 3)
                nodeKeys.add(new PrivateKey(Do.read("./src/test_node_config_v2_dynamic_test/node" + (i + 1) + "/tmp/node2_" + (i + 1) + ".private.unikey")));
            nodeKeysNew.add(new PrivateKey(Do.read("./src/test_node_config_v2_dynamic_test/node" + (i + 1) + "/tmp/node2_" + (i + 1) + ".private.unikey")));
        }

        List<NodeInfo> netConfig = mm.get(0).netConfig.toList();
        List<NodeInfo> netConfigNew = mm.get(3).netConfig.toList();

        for (Main m : mm) {
            m.shutdown();
        }
        mm.clear();

        Contract configContract = createNetConfigContract(netConfig,issuerKey);

        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");

        for (int i = 0; i < 4; i++) {
            mm.add(createMainFromDb(dbUrls.get(i), false));
        }

        Client client = new Client(TestKeys.privateKey(0), mm.get(0).myInfo, null);

        Parcel parcel = createParcelWithFreshU(client, configContract,Do.listOf(issuerKey));
        client.registerParcel(parcel.pack(),15000);

        ItemResult rr;
        while (true) {

            rr = client.getState(configContract.getId());
            Thread.currentThread().sleep(500);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state, ItemState.APPROVED);

        configContract = createNetConfigContract(configContract,netConfigNew,nodeKeys);

        parcel = createParcelWithFreshU(client, configContract,nodeKeys);
        client.registerParcel(parcel.pack(),15000);
        while (true) {

            rr = client.getState(configContract.getId());
            Thread.currentThread().sleep(500);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state, ItemState.APPROVED);
        Thread.sleep(1000);
        for (Main m : mm) {
            assertEquals(m.config.getPositiveConsensus(), 3);
        }
        configContract = createNetConfigContract(configContract,netConfig,nodeKeys);

        parcel = createParcelWithFreshU(client, configContract,nodeKeys);
        client.registerParcel(parcel.pack(),15000);
        while (true) {

            rr = client.getState(configContract.getId());
            Thread.currentThread().sleep(500);
            if (!rr.state.isPending())
                break;
        }
        assertEquals(rr.state, ItemState.APPROVED);
        Thread.sleep(1000);
        for (Main m : mm) {
            assertEquals(m.config.getPositiveConsensus(), 2);
        }

        for (Main m : mm) {
            m.shutdown();
        }
    }

    private Contract createNetConfigContract(Contract contract, List<NodeInfo> netConfig, Collection<PrivateKey> currentConfigKeys) throws IOException {
        contract = contract.createRevision();
        ListRole listRole = new ListRole("owner");
        for(NodeInfo ni: netConfig) {
            SimpleRole role = new SimpleRole(ni.getName());
            contract.registerRole(role);
            role.addKeyRecord(new KeyRecord(ni.getPublicKey()));
            listRole.addRole(role);
        }
        listRole.setQuorum(netConfig.size()-1);
        contract.registerRole(listRole);
        contract.getStateData().set("net_config",netConfig);
        List<KeyRecord> creatorKeys = new ArrayList<>();
        for(PrivateKey key : currentConfigKeys) {
            creatorKeys.add(new KeyRecord(key.getPublicKey()));
            contract.addSignerKey(key);
        }
        contract.setCreator(creatorKeys);
        contract.seal();
        return contract;
    }

    private Contract createNetConfigContract(List<NodeInfo> netConfig,PrivateKey issuerKey) throws IOException {
        Contract contract = new Contract();
        contract.setIssuerKeys(issuerKey.getPublicKey());
        contract.registerRole(new RoleLink("creator", "issuer"));
        ListRole listRole = new ListRole("owner");
        for(NodeInfo ni: netConfig) {
            SimpleRole role = new SimpleRole(ni.getName());
            contract.registerRole(role);
            role.addKeyRecord(new KeyRecord(ni.getPublicKey()));
            listRole.addRole(role);
        }
        listRole.setQuorum(netConfig.size()-1);
        contract.registerRole(listRole);
        RoleLink ownerLink = new RoleLink("ownerlink","owner");
        ChangeOwnerPermission changeOwnerPermission = new ChangeOwnerPermission(ownerLink);
        HashMap<String,Object> fieldsMap = new HashMap<>();
        fieldsMap.put("net_config",null);
        Binder modifyDataParams = Binder.of("fields",fieldsMap);
        ModifyDataPermission modifyDataPermission = new ModifyDataPermission(ownerLink,modifyDataParams);
        contract.addPermission(changeOwnerPermission);
        contract.addPermission(modifyDataPermission);
        contract.setExpiresAt(ZonedDateTime.now().plusYears(40));
        contract.getStateData().set("net_config",netConfig);
        contract.addSignerKey(issuerKey);
        contract.seal();
        return contract;
    }

    @Ignore
    @Test
    public void checkVerbose() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);

        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }
        System.out.println("---------- verbose nothing ---------------");

        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.network.getVerboseLevel());
        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.node.getVerboseLevel());

        Contract testContract = new Contract(myKey);
        testContract.seal();
        assertTrue(testContract.isOk());
        Parcel parcel = createParcelWithFreshU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack(), 1000);
        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());

        main.setVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
        System.out.println("---------- verbose base ---------------");

        Contract testContract2 = new Contract(myKey);
        testContract2.seal();
        assertTrue(testContract2.isOk());
        Parcel parcel2 = createParcelWithFreshU(client, testContract2,Do.listOf(myKey));
        client.registerParcel(parcel2.pack(), 1000);
        ItemResult itemResult2 = client.getState(parcel2.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.BASE, main.network.getVerboseLevel());
        assertEquals (DatagramAdapter.VerboseLevel.BASE, main.node.getVerboseLevel());

        main.setVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);
        System.out.println("---------- verbose nothing ---------------");

        Contract testContract3 = new Contract(myKey);
        testContract3.seal();
        assertTrue(testContract3.isOk());
        Parcel parcel3 = createParcelWithFreshU(client, testContract3,Do.listOf(myKey));
        client.registerParcel(parcel3.pack(), 1000);
        ItemResult itemResult3 = client.getState(parcel3.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.network.getVerboseLevel());
        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.node.getVerboseLevel());

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkUDPVerbose() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);

        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }
        System.out.println("---------- verbose nothing ---------------");

        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.network.getUDPVerboseLevel());

        Contract testContract = new Contract(myKey);
        testContract.seal();
        assertTrue(testContract.isOk());
        Parcel parcel = createParcelWithFreshU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack(), 1000);
        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());

        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
        System.out.println("---------- verbose base ---------------");

        Contract testContract2 = new Contract(myKey);
        testContract2.seal();
        assertTrue(testContract2.isOk());
        Parcel parcel2 = createParcelWithFreshU(client, testContract2,Do.listOf(myKey));
        client.registerParcel(parcel2.pack(), 1000);
        ItemResult itemResult2 = client.getState(parcel2.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.BASE, main.network.getUDPVerboseLevel());

        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);

        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.DETAILED);
        System.out.println("---------- verbose detailed ---------------");

        Contract testContract4 = new Contract(myKey);
        testContract4.seal();
        assertTrue(testContract4.isOk());
        Parcel parcel4 = createParcelWithFreshU(client, testContract4,Do.listOf(myKey));
        client.registerParcel(parcel4.pack(), 1000);
        ItemResult itemResult4 = client.getState(parcel4.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.DETAILED, main.network.getUDPVerboseLevel());

        main.setUDPVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING);
        System.out.println("---------- verbose nothing ---------------");

        Contract testContract3 = new Contract(myKey);
        testContract3.seal();
        assertTrue(testContract3.isOk());
        Parcel parcel3 = createParcelWithFreshU(client, testContract3,Do.listOf(myKey));
        client.registerParcel(parcel3.pack(), 1000);
        ItemResult itemResult3 = client.getState(parcel3.getPayloadContract().getId());

        assertEquals (DatagramAdapter.VerboseLevel.NOTHING, main.network.getUDPVerboseLevel());

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkShutdown() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);

        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        Contract testContract = new Contract(myKey);
        for (int i = 0; i < 10; i++) {
            Contract nc = new Contract(myKey);
            testContract.addNewItems(nc);
        }
        testContract.seal();
        assertTrue(testContract.isOk());
        Parcel parcel = createParcelWithFreshU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack());
        System.out.println(">> before shutdown state: " + client.getState(parcel.getPayloadContract().getId()));
        System.out.println(">> before shutdown state: " + client.getState(parcel.getPayloadContract().getNew().get(0).getId()));

        main.shutdown();
        Thread.sleep(5000);

        mm.remove(main);
        main = createMain("node1", false);
        mm.add(main);
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }
        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());
        ItemResult itemResult2 = client.getState(parcel.getPayloadContract().getNew().get(0).getId());
        System.out.println(">> after shutdown state: " + itemResult + " and new " + itemResult2);

        while (itemResult.state.isPending()) {
            Thread.currentThread().sleep(500);
            itemResult = client.getState(parcel.getPayloadContract().getId());
            System.out.println(">> wait result: " + itemResult);
        }
        itemResult2 = client.getState(parcel.getPayloadContract().getNew().get(0).getId());

        assertEquals (ItemState.UNDEFINED, itemResult.state);
        assertEquals (ItemState.UNDEFINED, itemResult2.state);

        mm.forEach(x -> x.shutdown());
    }

    @Ignore
    @Test
    public void shutdownCycle() throws Exception {
        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");
        dbUrls.stream().forEach(url -> {
            try {
                clearLedger(url);
            } catch (Exception e) {
            }
        });
        for (int i=0; i < 50; i++) {
            checkShutdown();
            System.out.println("iteration " + i);
            Thread.sleep(5000);
            dbUrls.stream().forEach(url -> {
                try {
                    clearLedger(url);
                } catch (Exception e) {
                }
            });
        }
    }

    @Test
    public void checkRestartUDP() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);
        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        Contract testContract = new Contract(myKey);
        for (int i = 0; i < 10; i++) {
            Contract nc = new Contract(myKey);
            nc.seal();
            testContract.addNewItems(nc);
        }
        testContract.seal();
        assertTrue(testContract.isOk());

        Parcel parcel = createParcelWithFreshU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack());

        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());
        while (itemResult.state.isPending()) {
            Thread.currentThread().sleep(500);
            itemResult = client.getState(parcel.getPayloadContract().getId());
            System.out.println(">> wait result: " + itemResult);
        }
        ItemResult itemResult2 = client.getState(parcel.getPayloadContract().getNew().get(0).getId());

        System.out.println(">> before restart state: " + itemResult);
        System.out.println(">> before restart state: " + itemResult2);

        main.restartUDPAdapter();
        main.waitReady();

        itemResult = client.getState(parcel.getPayloadContract().getId());
        itemResult2 = client.getState(parcel.getPayloadContract().getNew().get(0).getId());
        System.out.println(">> after restart state: " + itemResult + " and new " + itemResult2);

        while (itemResult.state.isPending()) {
            Thread.currentThread().sleep(500);
            itemResult = client.getState(parcel.getPayloadContract().getId());
            System.out.println(">> wait result: " + itemResult);
        }

        Thread.sleep(7000);
        itemResult2 = client.getState(parcel.getPayloadContract().getNew().get(0).getId());

        assertEquals (ItemState.APPROVED, itemResult.state);
        assertEquals (ItemState.APPROVED, itemResult2.state);

        mm.forEach(x -> x.shutdown());
    }

    public synchronized Parcel createParcelWithFreshU(Client client, Contract c, Collection<PrivateKey> keys) throws Exception {
        Set<PublicKey> ownerKeys = new HashSet();
        keys.stream().forEach(key->ownerKeys.add(key.getPublicKey()));
        Contract stepaU = InnerContractsService.createFreshU(100000000, ownerKeys);
        stepaU.check();
        stepaU.traceErrors();

        PrivateKey clientPrivateKey = client.getSession().getPrivateKey();
        PrivateKey newPrivateKey = new PrivateKey(Do.read("./src/test_contracts/keys/u_key.private.unikey"));
        client.getSession().setPrivateKey(newPrivateKey);
        client.restart();

        ItemResult itemResult = client.register(stepaU.getPackedTransaction(), 5000);

        client.getSession().setPrivateKey(clientPrivateKey);
        client.restart();

        assertEquals(ItemState.APPROVED, itemResult.state);
        Set<PrivateKey> keySet = new HashSet<>();
        keySet.addAll(keys);
        return ContractsService.createParcel(c, stepaU, 150, keySet);
    }

    @Test
    public void registerContractWithAnonymousId() throws Exception {
        TestSpace ts = prepareTestSpace();
        PrivateKey newPrivateKey = new PrivateKey(Do.read("./src/test_contracts/keys/u_key.private.unikey"));

        byte[] myAnonId = newPrivateKey.createAnonymousId();

        Contract contract = new Contract();
        contract.setExpiresAt(ZonedDateTime.now().plusDays(90));
        Role r = contract.setIssuerKeys(AnonymousId.fromBytes(myAnonId));
        contract.registerRole(new RoleLink("owner", "issuer"));
        contract.registerRole(new RoleLink("creator", "issuer"));
        contract.addPermission(new ChangeOwnerPermission(r));
        contract.addSignerKey(newPrivateKey);
        contract.seal();

        assertTrue(contract.isOk());
        System.out.println("contract.check(): " + contract.check());
        contract.traceErrors();

        ts.client.getSession().setPrivateKey(newPrivateKey);
        ts.client.restart();

        ItemResult itemResult = ts.client.register(contract.getPackedTransaction(), 5000);

        assertEquals(ItemState.APPROVED, itemResult.state);

        ts.nodes.forEach(x -> x.shutdown());
    }

    private TestSpace prepareTestSpace() throws Exception {
        return prepareTestSpace(TestKeys.privateKey(3));
    }

    private TestSpace prepareTestSpace(PrivateKey key) throws Exception {
        TestSpace testSpace = new TestSpace();
        testSpace.nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            testSpace.nodes.add(createMain("node" + (i + 1), false));
        testSpace.node = testSpace.nodes.get(0);
        assertEquals("http://localhost:8080", testSpace.node.myInfo.internalUrlString());
        assertEquals("http://localhost:8080", testSpace.node.myInfo.publicUrlString());
        testSpace.myKey = key;
        testSpace.client = new Client(testSpace.myKey, testSpace.node.myInfo, null);

        testSpace.clients = new ArrayList();
        for (int i = 0; i < 4; i++)
            testSpace.clients.add(new Client(testSpace.myKey, testSpace.nodes.get(i).myInfo, null));
        return testSpace;
    }

    private class TestSpace {
        public List<Main> nodes = null;
        public Main node = null;
        PrivateKey myKey = null;
        Client client = null;
        Object uContractLock = new Object();
        Contract uContract = null;
        public ArrayList<Client> clients;
    }

    private static final int MAX_PACKET_SIZE = 512;
    protected void sendBlock(UDPAdapter.Packet packet, DatagramSocket socket, NodeInfo destination) throws InterruptedException {

        byte[] out = packet.makeByteArray();
        DatagramPacket dp = new DatagramPacket(out, out.length, destination.getNodeAddress().getAddress(), destination.getNodeAddress().getPort());

        try {
            socket.send(dp);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected void sendHello(NodeInfo myNodeInfo, NodeInfo destination, UDPAdapter udpAdapter, DatagramSocket socket) throws InterruptedException {
//      System.out.println(">> send froud from " + myNodeInfo.getNumber() + " to " + destination.getNumber());
        Binder binder = Binder.fromKeysValues(
                "data", myNodeInfo.getNumber()
        );
        UDPAdapter.Packet packet = udpAdapter.createTestPacket(
                new Random().nextInt(Integer.MAX_VALUE),
                myNodeInfo.getNumber(),
                destination.getNumber(),
                UDPAdapter.PacketTypes.HELLO,
                Boss.pack(binder));
        sendBlock(packet, socket, destination);
    }

    protected void sendWelcome(NodeInfo myNodeInfo, NodeInfo destination, UDPAdapter udpAdapter, DatagramSocket socket) throws Exception {
        byte[] payload = new PublicKey(destination.getPublicKey().pack()).encrypt(Do.randomBytes(64));
        UDPAdapter.Packet packet = udpAdapter.createTestPacket(
                new Random().nextInt(Integer.MAX_VALUE),
                myNodeInfo.getNumber(),
                destination.getNumber(),
                UDPAdapter.PacketTypes.WELCOME,
                payload);
        sendBlock(packet, socket, destination);
    }

    protected void sendDataGarbage(NodeInfo myNodeInfo, NodeInfo destination, UDPAdapter udpAdapter, DatagramSocket socket) throws Exception {
        byte[] data = new PublicKey(destination.getPublicKey().pack()).encrypt(Do.randomBytes(64));
        byte[] crc32 = new Crc32().digest(data);
        byte[] payload = new byte[data.length + crc32.length];
        System.arraycopy(data, 0, payload, 0, data.length);
        System.arraycopy(crc32, 0, payload, data.length, crc32.length);
        UDPAdapter.Packet packet = udpAdapter.createTestPacket(
                new Random().nextInt(Integer.MAX_VALUE),
                myNodeInfo.getNumber(),
                destination.getNumber(),
                UDPAdapter.PacketTypes.DATA,
                payload);
        sendBlock(packet, socket, destination);
    }

    @Ignore
    @Test
    public void udpDisruptionTest() throws Exception{
        List<Main> mm = new ArrayList<>();
        final int NODE_COUNT = 4;
        final int PORT_BASE = 12000;
        final int TEST_MODE = UDPAdapter.PacketTypes.HELLO;
        //final int TEST_MODE = UDPAdapter.PacketTypes.WELCOME;
        //final int TEST_MODE = UDPAdapter.PacketTypes.DATA;

        for (int i = 0; i < NODE_COUNT; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }
//        mm.get(0).setUDPVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
//        mm.get(1).setUDPVerboseLevel(DatagramAdapter.VerboseLevel.DETAILED);

        class TestRunnable implements Runnable {

            int finalI;
            int finalJ;
            boolean alive = true;

            @Override
            public void run() {
                try {
                    NodeInfo source = mm.get(finalI).myInfo;
                    NodeInfo destination = mm.get(finalJ).myInfo;
                    DatagramSocket socket = new DatagramSocket(PORT_BASE+ finalI*NODE_COUNT+finalJ);

                    while (alive) {
                        if (TEST_MODE == UDPAdapter.PacketTypes.HELLO)
                            sendHello(source,destination,mm.get(finalI).network.getUDPAdapter(),socket);
                        else if (TEST_MODE == UDPAdapter.PacketTypes.WELCOME)
                            sendWelcome(source,destination,mm.get(finalI).network.getUDPAdapter(),socket);
                        else
                            sendDataGarbage(source,destination,mm.get(finalI).network.getUDPAdapter(),socket);
                        Thread.sleep(4);
                    }
                } catch (Exception e) {
                    System.out.println("runnable exception: " + e.toString());
                }
            }
        }

        List<Thread> threadsList = new ArrayList<>();
        List<TestRunnable> runnableList = new ArrayList<>();
        for(int i = 0; i < NODE_COUNT; i++) {
            for(int j = 0; j < NODE_COUNT;j++) {
                if(j == i)
                    continue;
                final int finalI = i;
                final int finalJ = j;
                TestRunnable runnableSingle = new TestRunnable();
                runnableList.add(runnableSingle);
                threadsList.add(
                new Thread(() -> {
                    runnableSingle.finalI = finalI;
                    runnableSingle.finalJ = finalJ;
                    runnableSingle.run();

                }));
            }
        }

        for (Thread th : threadsList) {
            th.start();
        }
        Thread.sleep(5000);

        PrivateKey myKey = TestKeys.privateKey(0);
        Client client = new Client(myKey,mm.get(0).myInfo,null);

        Contract contract = new Contract(myKey);
        contract.seal();

        Parcel parcel = createParcelWithFreshU(client,contract,Do.listOf(myKey));
        client.registerParcel(parcel.pack(),60000);
        ItemResult rr;
        while(true) {
            rr = client.getState(contract.getId());
            if(!rr.state.isPending())
                break;
        }

        assertEquals(rr.state, ItemState.APPROVED);

        for (TestRunnable tr : runnableList) {
            tr.alive = false;
        }
        for (Thread th : threadsList) {
            th.interrupt();
        }
        mm.forEach(x -> x.shutdown());
    }

    @Ignore
    @Test
    public void dbSanitationTest() throws Exception {
        final int NODE_COUNT = 4;
        PrivateKey myKey = TestKeys.privateKey(NODE_COUNT);

        List<String> dbUrls = new ArrayList<>();
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t1");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t2");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t3");
        dbUrls.add("jdbc:postgresql://localhost:5432/universa_node_t4");
        List<Ledger> ledgers = new ArrayList<>();
        dbUrls.stream().forEach(url -> {
            try {
//                clearLedger(url);
                PostgresLedger ledger = new PostgresLedger(url);
                ledgers.add(ledger);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        Random random = new Random(123);

        List<Contract> origins = new ArrayList<>();
        List<Contract> newRevisions = new ArrayList<>();
        List<Contract> newContracts = new ArrayList<>();

        final int N = 100;
        for(int i = 0; i < N; i++) {
            Contract origin = new Contract(myKey);
            origin.seal();
            origins.add(origin);

            Contract newRevision = origin.createRevision(myKey);

            if(i < N/2) {
                //ACCEPTED
                newRevision.setOwnerKeys(TestKeys.privateKey(NODE_COUNT + 1).getPublicKey());
            } else {
                //DECLINED
                //State is equal
            }

            Contract newContract = new Contract(myKey);
            newRevision.addNewItems(newContract);
            newRevision.seal();

            newContracts.add(newContract);
            newRevisions.add(newRevision);
            int unfinishedNodesCount = random.nextInt(2)+1;
            Set<Integer> unfinishedNodesNumbers = new HashSet<>();
            while(unfinishedNodesCount > unfinishedNodesNumbers.size()) {
                unfinishedNodesNumbers.add(random.nextInt(NODE_COUNT)+1);
            }

            System.out.println("item# "+ newRevision.getId().toBase64String().substring(0,6) + " nodes " + unfinishedNodesNumbers.toString());
            int finalI = i;
            for(int j = 0; j < NODE_COUNT;j++) {
                boolean finished = !unfinishedNodesNumbers.contains(j+1);
                Ledger ledger = ledgers.get(j);


                StateRecord originRecord = ledger.findOrCreate(origin.getId());
                originRecord.setExpiresAt(origin.getExpiresAt());
                originRecord.setCreatedAt(origin.getCreatedAt());

                StateRecord newRevisionRecord = ledger.findOrCreate(newRevision.getId());
                newRevisionRecord.setExpiresAt(newRevision.getExpiresAt());
                newRevisionRecord.setCreatedAt(newRevision.getCreatedAt());

                StateRecord newContractRecord = ledger.findOrCreate(newContract.getId());
                newContractRecord.setExpiresAt(newContract.getExpiresAt());
                newContractRecord.setCreatedAt(newContract.getCreatedAt());

                if(finished) {
                    if(finalI < N/2) {
                        originRecord.setState(ItemState.REVOKED);
                        newContractRecord.setState(ItemState.APPROVED);
                        newRevisionRecord.setState(ItemState.APPROVED);
                    } else {
                        originRecord.setState(ItemState.APPROVED);
                        newContractRecord.setState(ItemState.UNDEFINED);
                        newRevisionRecord.setState(ItemState.DECLINED);
                    }
                } else {
                    originRecord.setState(ItemState.LOCKED);
                    originRecord.setLockedByRecordId(newRevisionRecord.getRecordId());
                    newContractRecord.setState(ItemState.LOCKED_FOR_CREATION);
                    newContractRecord.setLockedByRecordId(newRevisionRecord.getRecordId());
                    newRevisionRecord.setState(finalI < N/2 ? ItemState.PENDING_POSITIVE : ItemState.PENDING_NEGATIVE);
                }

                originRecord.save();
                ledger.putItem(originRecord,origin, Instant.now().plusSeconds(3600*24));
                newRevisionRecord.save();
                ledger.putItem(newRevisionRecord,newRevision, Instant.now().plusSeconds(3600*24));
                if(newContractRecord.getState() == ItemState.UNDEFINED) {
                    newContractRecord.destroy();
                } else {
                    newContractRecord.save();
                }
            }
        }
        ledgers.stream().forEach(ledger -> ledger.close());
        ledgers.clear();

        List<Main> mm = new ArrayList<>();
        List<Client> clients = new ArrayList<>();

        for (int i = 0; i < NODE_COUNT; i++) {
            Main m = createMain("node" + (i + 1), false);
            mm.add(m);
            Client client = new Client(TestKeys.privateKey(i), m.myInfo, null);
            clients.add(client);
        }

        while (true) {
            try {
                for(int i =0; i < NODE_COUNT; i++) {
                    clients.get(i).getState(newRevisions.get(0));
                }
                break;
            } catch (ClientError e) {
                Thread.sleep(1000);
                mm.stream().forEach( m -> System.out.println("node#" +m.myInfo.getNumber() + " is " +  (m.node.isSanitating() ? "" : "not ") + "sanitating"));
            }
        }

        Contract contract = new Contract(TestKeys.privateKey(3));
        contract.seal();
        ItemResult ir = clients.get(0).register(contract.getPackedTransaction(), 10000);
        ir.errors.toString();

        for(int i = 0; i < N; i++) {
            ItemResult rr = clients.get(i%NODE_COUNT).getState(newRevisions.get(i).getId());
            ItemState targetState = i < N/2 ? ItemState.APPROVED : ItemState.DECLINED;
            assertEquals(rr.state,targetState);
        }
        Thread.sleep(1000);
        mm.stream().forEach(m -> m.shutdown());
        Thread.sleep(1000);

        dbUrls.stream().forEach(url -> {
            try {
                PostgresLedger ledger = new PostgresLedger(url);
                assertTrue(ledger.findUnfinished().isEmpty());
                ledger.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void clearLedger(String url) throws Exception {
        Properties properties = new Properties();
        try(DbPool dbPool = new DbPool(url, properties, 64)) {
            try (PooledDb db = dbPool.db()) {
                try (PreparedStatement statement = db.statement("delete from items;")
                ) {
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = db.statement("delete from ledger;")
                ) {
                    statement.executeUpdate();
                }
            }
        }
    }


    @Test
    public void test123() throws Exception {
        ZonedDateTime now = ZonedDateTime.now();
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
        builder.appendValue(ChronoField.DAY_OF_MONTH,2);
        builder.appendLiteral("/");
        builder.appendValue(ChronoField.MONTH_OF_YEAR,2);
        builder.appendLiteral("/");
        builder.appendValue(ChronoField.YEAR,4);

        System.out.println(now.format(builder.toFormatter()));
        System.out.println(now.truncatedTo(ChronoUnit.DAYS).format(builder.toFormatter()));
        System.out.println(now.truncatedTo(ChronoUnit.DAYS).minusDays(now.getDayOfMonth()-1).format(builder.toFormatter()));
        System.out.println(now.truncatedTo(ChronoUnit.DAYS).minusDays(now.getDayOfMonth()-1).minusMonths(1).format(builder.toFormatter()));
    }

    @Ignore
    @Test
    public void nodeStatsTest() throws Exception {
        PrivateKey issuerKey = new PrivateKey(Do.read("./src/test_contracts/keys/reconfig_key.private.unikey"));
        TestSpace testSpace = prepareTestSpace(issuerKey);

        Thread.sleep(2000);
        Binder b = testSpace.client.getStats(90);
        int uptime = b.getIntOrThrow("uptime");

        testSpace.nodes.get(0).config.setStatsIntervalSmall(Duration.ofSeconds(4));
        testSpace.nodes.get(0).config.setStatsIntervalBig(Duration.ofSeconds(60));
        //testSpace.nodes.get(0).config.getKeysWhiteList().add(issuerKey.getPublicKey());
        testSpace.nodes.get(0).config.getAddressesWhiteList().add(new KeyAddress(issuerKey.getPublicKey(), 0, true));

        while(testSpace.client.getStats(null).getIntOrThrow("uptime") >= uptime) {
            Thread.sleep(500);
        }

        for (int i = 0; i < 30; i++) {
            Instant now = Instant.now();
            Contract contract = new Contract(issuerKey);
            contract.seal();
            testSpace.client.register(contract.getPackedTransaction(),1500);
            contract = new Contract(issuerKey);
            contract.seal();
            testSpace.client.register(contract.getPackedTransaction(),1500);

            Thread.sleep(4000-(Instant.now().toEpochMilli()-now.toEpochMilli()));
            Binder binder = testSpace.client.getStats(90);

            assertEquals(binder.getIntOrThrow("smallIntervalApproved"),2);
            int target = i < 15 ? (i+1)*2 : 30;
            assertTrue(binder.getIntOrThrow("bigIntervalApproved") <= target && binder.getIntOrThrow("bigIntervalApproved") >= target-2);
        }

        testSpace.nodes.forEach(x -> x.shutdown());
    }


    @Test
    public void resynItemTest() throws Exception {
        PrivateKey issuerKey = new PrivateKey(Do.read("./src/test_contracts/keys/reconfig_key.private.unikey"));
        TestSpace testSpace = prepareTestSpace(issuerKey);
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));

        //shutdown one of nodes
        int absentNode = testSpace.nodes.size()-1;
        testSpace.nodes.get(absentNode).shutdown();
        testSpace.nodes.remove(absentNode);

        //register contract in non full network
        Contract contract = new Contract(issuerKey);
        contract.seal();
        testSpace.client.register(contract.getPackedTransaction(),1500);
        assertEquals(testSpace.client.getState(contract.getId()).state,ItemState.APPROVED);


        //recreate network and make sure contract is still APPROVED
        testSpace.nodes.forEach(n->n.shutdown());
        Thread.sleep(2000);
        testSpace = prepareTestSpace(issuerKey);
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));
        assertEquals(testSpace.client.getState(contract.getId()).state,ItemState.APPROVED);


        StateRecord r0 = testSpace.nodes.get(0).node.getLedger().getRecord(contract.getId());
        r0.setExpiresAt(r0.getExpiresAt().minusHours(1));
        r0.save();


        StateRecord r1 = testSpace.nodes.get(1).node.getLedger().getRecord(contract.getId());
        r1.setCreatedAt(r1.getCreatedAt().plusHours(1));
        r1.save();

        //create client with absent node and check the contract
        Client absentNodeClient = new Client(testSpace.myKey,testSpace.nodes.get(absentNode).myInfo,null);
        assertEquals(absentNodeClient.getState(contract.getId()).state,ItemState.UNDEFINED);

        //start resync with a command
        absentNodeClient.resyncItem(contract.getId());

        //make sure resync didn't affect others
        assertEquals(testSpace.client.getState(contract.getId()).state,ItemState.APPROVED);


        //wait for new status
        ItemResult rr;
        while(true) {
            rr = absentNodeClient.getState(contract.getId());
            if(!rr.state.isPending())
                break;
            Thread.sleep(500);
        }
        assertEquals(rr.state,ItemState.APPROVED);



        Average createdAverage = new Average();
        Average expiredAverage = new Average();
        ItemResult ir0 = testSpace.nodes.get(0).node.checkItem(contract.getId());
        ItemResult ir1 = testSpace.nodes.get(1).node.checkItem(contract.getId());
        ItemResult ir2 = testSpace.nodes.get(2).node.checkItem(contract.getId());

        createdAverage.update(ir0.createdAt.toEpochSecond());
        createdAverage.update(ir2.createdAt.toEpochSecond());

        expiredAverage.update(ir1.expiresAt.toEpochSecond());
        expiredAverage.update(ir2.expiresAt.toEpochSecond());

        assertEquals(rr.createdAt.toEpochSecond(),(long)createdAverage.average());
        assertEquals(rr.expiresAt.toEpochSecond(),(long)expiredAverage.average());

        testSpace.nodes.forEach(x -> x.shutdown());

    }

    @Test
    public void resyncFromClient() throws Exception {
        TestSpace testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));
        testSpace.nodes.get(testSpace.nodes.size()-1).shutdown();
        Contract contractMoney = ContractsService.createTokenContract(
                new HashSet<>(Arrays.asList(TestKeys.privateKey(1))),
                new HashSet<>(Arrays.asList(TestKeys.publicKey(1))),
                "9000"
        );
        ItemResult ir1 = testSpace.client.register(contractMoney.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir1.state);

        //recreate nodes
        for (int i = 0; i < testSpace.nodes.size()-1; ++i)
            testSpace.nodes.get(i).shutdown();
        Thread.sleep(2000);
        testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));

        System.out.println("\n========== resyncing ==========\n");
        testSpace.nodes.get(testSpace.clients.size()-1).setVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
        testSpace.clients.get(testSpace.clients.size()-1).resyncItem(contractMoney.getId());
        long millisToWait = 60000;
        long waitPeriod = 2000;
        ItemResult ir = null;
        while (millisToWait > 0) {
            Thread.sleep(waitPeriod);
            millisToWait -= waitPeriod;
            ir = testSpace.clients.get(testSpace.clients.size()-1).getState(contractMoney.getId());
            if (ir.state == ItemState.APPROVED)
                break;
        }
        assertEquals(ItemState.APPROVED, ir.state);
        testSpace.nodes.forEach(n->n.shutdown());
    }

    @Test
    public void resyncSubItemsTest() throws Exception {
        TestSpace testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));
        testSpace.nodes.get(testSpace.nodes.size()-1).shutdown();

        Contract contractMoney = ContractsService.createTokenContract(
            new HashSet<>(Arrays.asList(TestKeys.privateKey(1))),
            new HashSet<>(Arrays.asList(TestKeys.publicKey(1))),
            "9000"
        );
        ItemResult ir1 = testSpace.client.register(contractMoney.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir1.state);

        List<Contract> splittedList = new ArrayList<>();
        Contract splitNest = contractMoney.createRevision();
        splitNest.addSignerKey(TestKeys.privateKey(1));
        Contract[] contracts = splitNest.split(10);
        Decimal valChange = new Decimal(contractMoney.getStateData().getStringOrThrow("amount"));
        for (int i = 0; i < contracts.length; ++i) {
            Contract splitted = contracts[i];
            Decimal val = new Decimal(i + 1);
            splitted.getStateData().set("amount", val);
            splittedList.add(splitted);
            valChange = valChange.subtract(val);
        }
        splitNest.getStateData().set("amount", valChange);

        splittedList.forEach(c -> System.out.println("splitted amount: " + c.getStateData().getStringOrThrow("amount")));
        System.out.println("contractMoney amount (revoke it): " + contractMoney.getStateData().getStringOrThrow("amount"));
        System.out.println("splitNest amount: " + splitNest.getStateData().getStringOrThrow("amount"));

        Contract splitBatch = new Contract(TestKeys.privateKey(1));
        splitBatch.addRevokingItems(contractMoney);
        splitNest.seal();
        splitBatch.addNewItems(splitNest);
        splittedList.forEach(c -> splitBatch.addNewItems(c));
        splitBatch.seal();

        ItemResult irSplitBatch = testSpace.client.register(splitBatch.getPackedTransaction(), 5000);
        Thread.sleep(1000);
        assertEquals(ItemState.APPROVED, irSplitBatch.state);
        assertEquals(ItemState.APPROVED, testSpace.client.getState(splitNest.getId()).state);
        for (Contract c : splittedList)
            assertEquals(ItemState.APPROVED, testSpace.client.getState(c.getId()).state);
        assertEquals(ItemState.REVOKED, testSpace.client.getState(contractMoney.getId()).state);

        //recreate nodes
        for (int i = 0; i < testSpace.nodes.size()-1; ++i)
            testSpace.nodes.get(i).shutdown();
        Thread.sleep(2000);
        testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));

        testSpace.clients.get(testSpace.clients.size()-1).resyncItem(splitNest.getId());
        Thread.sleep(2000);

        for (Contract c : splitBatch.getNew()) {
            for (int i = 0; i < testSpace.nodes.size(); ++i) {
                Main m = testSpace.nodes.get(i);
                if (c.getId().equals(splitNest.getId())) {
                    assertTrue(m.node.getLedger().getRecord(c.getId()).isApproved());
                } else {
                    if (i < testSpace.nodes.size() - 1)
                        assertTrue(m.node.getLedger().getRecord(c.getId()).isApproved());
                    else
                        assertNull(m.node.getLedger().getRecord(c.getId()));
                }
            }
        }

        //now join all
        testSpace.nodes.get(testSpace.clients.size()-1).setVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
        Contract joinAll = splitNest.createRevision();
        joinAll.getStateData().set("amount", "9000");
        splittedList.forEach(c -> joinAll.addRevokingItems(c));
        Decimal joinSum = new Decimal(0);
        for (Contract c : joinAll.getRevoking())
            joinSum = joinSum.add(new Decimal(c.getStateData().getStringOrThrow("amount")));
        joinAll.addSignerKey(TestKeys.privateKey(1));
        joinAll.seal();
        System.out.println("client: " + testSpace.clients.get(testSpace.clients.size()-1).getUrl());
        ItemResult irJoin = testSpace.clients.get(testSpace.clients.size()-1).register(joinAll.getPackedTransaction(), 5000);
        Thread.sleep(1000);
        assertEquals(ItemState.APPROVED, irJoin.state);
        System.out.println("joinAll amount: " + joinAll.getStateData().getStringOrThrow("amount"));
        for (Contract c : joinAll.getRevoking()) {
            for (int i = 0; i < testSpace.nodes.size(); ++i) {
                Main m = testSpace.nodes.get(i);
                assertTrue(m.node.getLedger().getRecord(c.getId()).isArchived());
            }
        }
        for (int i = 0; i < testSpace.nodes.size(); ++i) {
            Main m = testSpace.nodes.get(i);
            assertTrue(m.node.getLedger().getRecord(joinAll.getId()).isApproved());
        }

        testSpace.nodes.forEach(n -> n.shutdown());
    }

    @Test
    public void verboseLevelTest() throws Exception {
        PrivateKey issuerKey = new PrivateKey(Do.read("./src/test_contracts/keys/reconfig_key.private.unikey"));
        TestSpace testSpace = prepareTestSpace(issuerKey);

        Contract contract = new Contract(TestKeys.privateKey(3));
        contract.seal();
        testSpace.client.register(contract.getPackedTransaction(),8000);
        Thread.sleep(2000);
        testSpace.client.setVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING,DatagramAdapter.VerboseLevel.DETAILED,DatagramAdapter.VerboseLevel.NOTHING);
        contract = new Contract(TestKeys.privateKey(3));
        contract.seal();
        testSpace.client.register(contract.getPackedTransaction(),8000);

        testSpace.nodes.forEach(x -> x.shutdown());
    }


    @Test(timeout = 30000)
    public void freeRegistrationsAllowedFromCoreVersion() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Contract contract = new Contract(TestKeys.privateKey(0));
        contract.seal();
        ItemState expectedState = ItemState.UNDEFINED;
        if (Core.VERSION.contains("private"))
            expectedState = ItemState.APPROVED;
        System.out.println("Core.VERSION: " + Core.VERSION);
        System.out.println("expectedState: " + expectedState);
        ItemResult itemResult = client.register(contract.getPackedTransaction(), 5000);
        System.out.println("itemResult: " + itemResult);
        assertEquals(expectedState, itemResult.state);

        mm.forEach(x -> x.shutdown());
    }


    @Test(timeout = 30000)
    public void freeRegistrationsAllowedFromConfigOrVersion() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Contract contract = new Contract(TestKeys.privateKey(0));
        contract.seal();
        ItemState expectedState = ItemState.APPROVED;
        System.out.println("expectedState: " + expectedState);
        ItemResult itemResult = client.register(contract.getPackedTransaction(), 5000);
        System.out.println("itemResult: " + itemResult);
        assertEquals(expectedState, itemResult.state);

        main.config.setIsFreeRegistrationsAllowedFromYaml(false);
        contract = new Contract(TestKeys.privateKey(0));
        contract.seal();
        expectedState = ItemState.UNDEFINED;
        if (Core.VERSION.contains("private"))
            expectedState = ItemState.APPROVED;
        System.out.println("Core.VERSION: " + Core.VERSION);
        System.out.println("expectedState: " + expectedState);
        itemResult = client.register(contract.getPackedTransaction(), 5000);
        System.out.println("itemResult: " + itemResult);
        assertEquals(expectedState, itemResult.state);

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void testTokenContractApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> issuerPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));
        Set<PublicKey> issuerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(1)));
        Set<PublicKey> ownerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(2)));

        Contract tokenContract = ContractsService.createTokenContract(issuerPrivateKeys,ownerPublicKeys, "1000000");
        tokenContract.check();
        tokenContract.traceErrors();

        assertTrue(tokenContract.getOwner().isAllowedForKeys(ownerPublicKeys));
        assertTrue(tokenContract.getIssuer().isAllowedForKeys(issuerPrivateKeys));
        assertTrue(tokenContract.getCreator().isAllowedForKeys(issuerPrivateKeys));

        assertFalse(tokenContract.getOwner().isAllowedForKeys(issuerPrivateKeys));
        assertFalse(tokenContract.getIssuer().isAllowedForKeys(ownerPublicKeys));
        assertFalse(tokenContract.getCreator().isAllowedForKeys(ownerPublicKeys));

        assertTrue(tokenContract.getExpiresAt().isAfter(ZonedDateTime.now().plusMonths(3)));
        assertTrue(tokenContract.getCreatedAt().isBefore(ZonedDateTime.now()));

        assertEquals(InnerContractsService.getDecimalField(tokenContract, "amount"), new Decimal(1000000));

        assertEquals(tokenContract.getPermissions().get("split_join").size(), 1);

        Binder splitJoinParams = tokenContract.getPermissions().get("split_join").iterator().next().getParams();
        assertEquals(splitJoinParams.get("min_value"), 0.01);
        assertEquals(splitJoinParams.get("min_unit"), 0.01);
        assertEquals(splitJoinParams.get("field_name"), "amount");
        assertTrue(splitJoinParams.get("join_match_fields") instanceof List);
        assertEquals(((List)splitJoinParams.get("join_match_fields")).get(0), "state.origin");

        assertTrue(tokenContract.isPermitted("revoke", ownerPublicKeys));
        assertTrue(tokenContract.isPermitted("revoke", issuerPublicKeys));

        assertTrue(tokenContract.isPermitted("change_owner", ownerPublicKeys));
        assertFalse(tokenContract.isPermitted("change_owner", issuerPublicKeys));

        assertTrue(tokenContract.isPermitted("split_join", ownerPublicKeys));
        assertFalse(tokenContract.isPermitted("split_join", issuerPublicKeys));

        ItemResult itemResult = client.register(tokenContract.getPackedTransaction(), 5000);
        System.out.println("token contract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testMintableTokenContractApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> issuerPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));
        Set<PublicKey> issuerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(1)));
        Set<PublicKey> ownerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(2)));

        Contract mintableTokenContract = ContractsService.createTokenContractWithEmission(issuerPrivateKeys, ownerPublicKeys, "300000000000");

        mintableTokenContract.check();
        mintableTokenContract.traceErrors();

        ItemResult itemResult = client.register(mintableTokenContract.getPackedTransaction(), 5000);
        System.out.println("mintableTokenContract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        Contract emittedContract = ContractsService.createTokenEmission(mintableTokenContract, "100000000000", issuerPrivateKeys);

        emittedContract.check();
        emittedContract.traceErrors();

        assertEquals(emittedContract.getPermissions().get("split_join").size(), 1);

        Binder splitJoinParams = emittedContract.getPermissions().get("split_join").iterator().next().getParams();
        assertEquals(splitJoinParams.get("min_value"), 0.01);
        assertEquals(splitJoinParams.get("min_unit"), 0.01);
        assertEquals(splitJoinParams.get("field_name"), "amount");
        assertTrue(splitJoinParams.get("join_match_fields") instanceof List);
        assertEquals(((List)splitJoinParams.get("join_match_fields")).get(0), "state.origin");


        assertTrue(emittedContract.isPermitted("revoke", ownerPublicKeys));
        assertTrue(emittedContract.isPermitted("revoke", issuerPublicKeys));

        assertTrue(emittedContract.isPermitted("change_owner", ownerPublicKeys));
        assertFalse(emittedContract.isPermitted("change_owner", issuerPublicKeys));

        assertTrue(emittedContract.isPermitted("split_join", ownerPublicKeys));
        assertFalse(emittedContract.isPermitted("split_join", issuerPublicKeys));


        itemResult = client.register(emittedContract.getPackedTransaction(), 5000);
        System.out.println("emittedContract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        assertEquals(emittedContract.getStateData().getString("amount"), "400000000000");
        assertEquals(ItemState.REVOKED, main.node.waitItem(mintableTokenContract.getId(), 8000).state);

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testSplitAndJoinApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> issuerPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));
        Set<PublicKey> ownerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(2)));
        Set<PrivateKey> issuerPrivateKeys2 = new HashSet<>(Arrays.asList(TestKeys.privateKey(2)));

        Contract contractC = ContractsService.createTokenContract(issuerPrivateKeys,ownerPublicKeys, "100");
        contractC.check();
        contractC.traceErrors();

        ItemResult itemResult = client.register(contractC.getPackedTransaction(), 5000);
        System.out.println("contractC itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        // 100 - 30 = 70
        Contract сontractA = ContractsService.createSplit(contractC, "30", "amount", issuerPrivateKeys2, true);
        Contract contractB = сontractA.getNew().get(0);
        assertEquals("70", сontractA.getStateData().get("amount").toString());
        assertEquals("30", contractB.getStateData().get("amount").toString());

        itemResult = client.register(сontractA.getPackedTransaction(), 5000);
        System.out.println("сontractA itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        assertEquals("70", сontractA.getStateData().get("amount").toString());
        assertEquals("30", contractB.getStateData().get("amount").toString());

        assertEquals(ItemState.REVOKED, main.node.waitItem(contractC.getId(), 5000).state);
        assertEquals(ItemState.APPROVED, main.node.waitItem(сontractA.getId(), 5000).state);
        assertEquals(ItemState.APPROVED, main.node.waitItem(contractB.getId(), 5000).state);

        // join 70 + 30 = 100
        Contract contractC2 = ContractsService.createJoin(сontractA, contractB, "amount", issuerPrivateKeys2);
        contractC2.check();
        contractC2.traceErrors();
        assertTrue(contractC2.isOk());

        itemResult = client.register(contractC2.getPackedTransaction(), 5000);
        System.out.println("contractC2 itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        assertEquals(new Decimal(100), contractC2.getStateData().get("amount"));

        assertEquals(ItemState.REVOKED, main.node.waitItem(contractC.getId(), 5000).state);
        assertEquals(ItemState.REVOKED, main.node.waitItem(сontractA.getId(), 5000).state);
        assertEquals(ItemState.REVOKED, main.node.waitItem(contractB.getId(), 5000).state);
        assertEquals(ItemState.APPROVED, main.node.waitItem(contractC2.getId(), 5000).state);

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testShareContractApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> issuerPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));
        Set<PublicKey> issuerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(1)));
        Set<PublicKey> ownerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(2)));

        Contract shareContract = ContractsService.createShareContract(issuerPrivateKeys,ownerPublicKeys,"100");

        shareContract.check();
        shareContract.traceErrors();

        assertTrue(shareContract.getOwner().isAllowedForKeys(ownerPublicKeys));
        assertTrue(shareContract.getIssuer().isAllowedForKeys(issuerPrivateKeys));
        assertTrue(shareContract.getCreator().isAllowedForKeys(issuerPrivateKeys));

        assertFalse(shareContract.getOwner().isAllowedForKeys(issuerPrivateKeys));
        assertFalse(shareContract.getIssuer().isAllowedForKeys(ownerPublicKeys));
        assertFalse(shareContract.getCreator().isAllowedForKeys(ownerPublicKeys));

        assertTrue(shareContract.getExpiresAt().isAfter(ZonedDateTime.now().plusMonths(3)));
        assertTrue(shareContract.getCreatedAt().isBefore(ZonedDateTime.now()));

        assertEquals(InnerContractsService.getDecimalField(shareContract, "amount"), new Decimal(100));

        assertEquals(shareContract.getPermissions().get("split_join").size(), 1);

        Binder splitJoinParams = shareContract.getPermissions().get("split_join").iterator().next().getParams();
        assertEquals(splitJoinParams.get("min_value"), 1);
        assertEquals(splitJoinParams.get("min_unit"), 1);
        assertEquals(splitJoinParams.get("field_name"), "amount");
        assertTrue(splitJoinParams.get("join_match_fields") instanceof List);
        assertEquals(((List)splitJoinParams.get("join_match_fields")).get(0), "state.origin");

        assertTrue(shareContract.isPermitted("revoke", ownerPublicKeys));
        assertTrue(shareContract.isPermitted("revoke", issuerPublicKeys));

        assertTrue(shareContract.isPermitted("change_owner", ownerPublicKeys));
        assertFalse(shareContract.isPermitted("change_owner", issuerPublicKeys));

        assertTrue(shareContract.isPermitted("split_join", ownerPublicKeys));
        assertFalse(shareContract.isPermitted("split_join", issuerPublicKeys));

        ItemResult itemResult = client.register(shareContract.getPackedTransaction(), 5000);
        System.out.println("shareContract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testNotaryContractApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> issuerPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));
        Set<PublicKey> issuerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(1)));
        Set<PublicKey> ownerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(2)));

        Contract notaryContract = ContractsService.createNotaryContract(issuerPrivateKeys, ownerPublicKeys);

        notaryContract.check();
        notaryContract.traceErrors();

        assertTrue(notaryContract.getOwner().isAllowedForKeys(ownerPublicKeys));
        assertTrue(notaryContract.getIssuer().isAllowedForKeys(issuerPrivateKeys));
        assertTrue(notaryContract.getCreator().isAllowedForKeys(issuerPrivateKeys));

        assertFalse(notaryContract.getOwner().isAllowedForKeys(issuerPrivateKeys));
        assertFalse(notaryContract.getIssuer().isAllowedForKeys(ownerPublicKeys));
        assertFalse(notaryContract.getCreator().isAllowedForKeys(ownerPublicKeys));

        assertTrue(notaryContract.getExpiresAt().isAfter(ZonedDateTime.now().plusMonths(3)));
        assertTrue(notaryContract.getCreatedAt().isBefore(ZonedDateTime.now()));

        assertTrue(notaryContract.isPermitted("revoke", ownerPublicKeys));
        assertTrue(notaryContract.isPermitted("revoke", issuerPublicKeys));

        assertTrue(notaryContract.isPermitted("change_owner", ownerPublicKeys));
        assertFalse(notaryContract.isPermitted("change_owner", issuerPublicKeys));

        ItemResult itemResult = client.register(notaryContract.getPackedTransaction(), 5000);
        System.out.println("notaryContract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testTwoSignedContractApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> martyPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));
        Set<PrivateKey> stepaPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(2)));
        Set<PublicKey> stepaPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(2)));

        Contract baseContract = Contract.fromDslFile(ROOT_PATH + "DeLoreanOwnership.yml");
        PrivateKey manufacturePrivateKey = new PrivateKey(Do.read(ROOT_PATH + "_xer0yfe2nn1xthc.private.unikey"));

        baseContract.addSignerKey(manufacturePrivateKey);
        baseContract.seal();

        baseContract.check();
        baseContract.traceErrors();

        Contract twoSignContract = ContractsService.createTwoSignedContract(baseContract, martyPrivateKeys, stepaPublicKeys, false);

        //now emulate sending transaction pack through network ---->

        twoSignContract = Contract.fromPackedTransaction(twoSignContract.getPackedTransaction());

        twoSignContract.addSignatureToSeal(stepaPrivateKeys);

        twoSignContract.check();
        twoSignContract.traceErrors();

        ItemResult itemResult = client.register(twoSignContract.getPackedTransaction(), 5000);
        System.out.println("twoSignContract itemResult: " + itemResult);
        assertEquals(ItemState.DECLINED, itemResult.state);


        //now emulate sending transaction pack through network <----

        twoSignContract = Contract.fromPackedTransaction(twoSignContract.getPackedTransaction());

        twoSignContract.addSignatureToSeal(martyPrivateKeys);

        twoSignContract.check();
        twoSignContract.traceErrors();
        System.out.println("Contract with two signature is valid: " + twoSignContract.isOk());

        itemResult = client.register(twoSignContract.getPackedTransaction(), 5000);
        System.out.println("twoSignContract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testSlotApi() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Decimal kilobytesAndDaysPerU = client.storageGetRate();
        System.out.println("storageGetRate: " + kilobytesAndDaysPerU);
        assertEquals(new Decimal((int) main.config.getRate("SLOT1")), kilobytesAndDaysPerU);

        Contract simpleContract = new Contract(TestKeys.privateKey(1));
        simpleContract.seal();
        ItemResult itemResult = client.register(simpleContract.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, itemResult.state);

        SlotContract slotContract = ContractsService.createSlotContract(new HashSet<>(Arrays.asList(TestKeys.privateKey(1))), new HashSet<>(Arrays.asList(TestKeys.publicKey(1))), nodeInfoProvider);
        slotContract.setNodeInfoProvider(nodeInfoProvider);
        slotContract.putTrackingContract(simpleContract);

        Contract stepaU = InnerContractsService.createFreshU(100000000, new HashSet<>(Arrays.asList(TestKeys.publicKey(1))));
        itemResult = client.register(stepaU.getPackedTransaction(), 5000);
        System.out.println("stepaU itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        Parcel parcel = ContractsService.createPayingParcel(slotContract.getTransactionPack(), stepaU, 1, 100, new HashSet<>(Arrays.asList(TestKeys.privateKey(1))), false);

        Binder slotInfo = client.querySlotInfo(slotContract.getId());
        System.out.println("slot info is null: " + (slotInfo == null));
        assertNull(slotInfo);

        byte[] simpleContractBytes = client.queryContract(slotContract.getId(), null, simpleContract.getId());
        System.out.println("simpleContractBytes (by contractId): " + simpleContractBytes);
        assertEquals(false, Arrays.equals(simpleContract.getPackedTransaction(), simpleContractBytes));

        simpleContractBytes = client.queryContract(slotContract.getId(), simpleContract.getOrigin(), null);
        System.out.println("simpleContractBytes (by originId): " + simpleContractBytes);
        assertEquals(false, Arrays.equals(simpleContract.getPackedTransaction(), simpleContractBytes));

        client.registerParcel(parcel.pack(), 5000);
        itemResult = client.getState(slotContract.getId());
        System.out.println("slot itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        slotInfo = client.querySlotInfo(slotContract.getId());
        System.out.println("slot info size: " + slotInfo.size());
        assertNotNull(slotInfo);

        simpleContractBytes = client.queryContract(slotContract.getId(), null, simpleContract.getId());
        System.out.println("simpleContractBytes (by contractId) length: " + simpleContractBytes.length);
        assertEquals(true, Arrays.equals(simpleContract.getPackedTransaction(), simpleContractBytes));

        simpleContractBytes = client.queryContract(slotContract.getId(), simpleContract.getOrigin(), null);
        System.out.println("simpleContractBytes (by originId) length: " + simpleContractBytes.length);
        assertEquals(true, Arrays.equals(simpleContract.getPackedTransaction(), simpleContractBytes));

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testUnsApi() throws Exception {

        Set<PrivateKey> manufacturePrivateKeys = new HashSet<>();
        manufacturePrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "_xer0yfe2nn1xthc.private.unikey")));
        Set<PublicKey> manufacturePublicKeys = new HashSet<>();
        manufacturePublicKeys.add(manufacturePrivateKeys.iterator().next().getPublicKey());

        TestSpace testSpace = prepareTestSpace(manufacturePrivateKeys.iterator().next());

        PrivateKey authorizedNameServiceKey = TestKeys.privateKey(3);
        testSpace.nodes.forEach( m -> {
            m.config.setAuthorizedNameServiceCenterKeyData(new Bytes(authorizedNameServiceKey.getPublicKey().pack()));
            m.config.setIsFreeRegistrationsAllowedFromYaml(true);
        });

        Decimal namesAndDaysPerU = testSpace.client.unsRate();
        System.out.println("unsRate: " + namesAndDaysPerU);
        assertEquals(testSpace.node.config.getRate("UNS1"), namesAndDaysPerU.doubleValue(), 0.000001);

        Contract simpleContract = new Contract(manufacturePrivateKeys.iterator().next());
        simpleContract.seal();
        ItemResult itemResult = testSpace.client.register(simpleContract.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, itemResult.state);

        String unsTestName = "testContractName" + Instant.now().getEpochSecond();

        // check uns contract with origin record
        UnsContract unsContract = ContractsService.createUnsContractForRegisterContractName(manufacturePrivateKeys,
                manufacturePublicKeys, nodeInfoProvider, unsTestName, "test contract name", "http://test.com", simpleContract);
        unsContract.getUnsName(unsTestName).setUnsReducedName(unsTestName);
        unsContract.addSignerKey(authorizedNameServiceKey);
        unsContract.seal();
        unsContract.check();
        unsContract.traceErrors();

        Contract paymentContract = getApprovedUContract(testSpace);

        Parcel payingParcel = ContractsService.createPayingParcel(unsContract.getTransactionPack(), paymentContract, 1, 2000, manufacturePrivateKeys, false);

        Binder nameInfo = testSpace.client.queryNameRecord(simpleContract.getId());
        String name = nameInfo.getString("name", null);
        System.out.println("name info is null: " + (name == null));
        assertNull(name);

        byte[] unsContractBytes = testSpace.client.queryNameContract(unsTestName);
        System.out.println("unsContractBytes: " + unsContractBytes);
        assertEquals(false, Arrays.equals(unsContract.getPackedTransaction(), unsContractBytes));

        testSpace.client.registerParcel(payingParcel.pack(), 8000);
        itemResult = testSpace.client.getState(unsContract.getId());
        System.out.println("Uns itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        nameInfo = testSpace.client.queryNameRecord(simpleContract.getId());
        assertNotNull(nameInfo);
        System.out.println("name info size: " + nameInfo.size());
        System.out.println("Name: " + nameInfo.getString("name", ""));
        System.out.println("Description: " + nameInfo.getString("description", ""));
        System.out.println("URL: " + nameInfo.getString("url", ""));
        assertEquals(unsTestName, nameInfo.getString("name", ""));

        unsContractBytes = testSpace.client.queryNameContract(unsTestName);
        System.out.println("unsContractBytes: " + unsContractBytes);
        assertEquals(true, Arrays.equals(unsContract.getPackedTransaction(), unsContractBytes));

        // check uns contract with address record
        unsTestName = "testAddressContractName" + Instant.now().getEpochSecond();
        PrivateKey randomPrivKey = new PrivateKey(2048);

        UnsContract unsContract2 = ContractsService.createUnsContractForRegisterKeyName(manufacturePrivateKeys,
                manufacturePublicKeys, nodeInfoProvider, unsTestName, "test address name", "http://test.com", randomPrivKey.getPublicKey());
        unsContract2.getUnsName(unsTestName).setUnsReducedName(unsTestName);
        unsContract2.addSignerKey(authorizedNameServiceKey);
        unsContract2.addSignerKey(randomPrivKey);
        unsContract2.seal();
        unsContract2.check();
        unsContract2.traceErrors();

        paymentContract = getApprovedUContract(testSpace);

        payingParcel = ContractsService.createPayingParcel(unsContract2.getTransactionPack(), paymentContract, 1, 2000, manufacturePrivateKeys, false);

        KeyAddress keyAddr = new KeyAddress(randomPrivKey.getPublicKey(), 0, true);
        nameInfo = testSpace.client.queryNameRecord(keyAddr.toString());
        name = nameInfo.getString("name", null);
        System.out.println("name info is null: " + (name == null));
        assertNull(name);

        unsContractBytes = testSpace.client.queryNameContract(unsTestName);
        System.out.println("unsContractBytes: " + unsContractBytes);
        assertEquals(false, Arrays.equals(unsContract2.getPackedTransaction(), unsContractBytes));

        testSpace.client.registerParcel(payingParcel.pack(), 8000);
        itemResult = testSpace.client.getState(unsContract2.getId());
        System.out.println("Uns itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);

        nameInfo = testSpace.client.queryNameRecord(keyAddr.toString());
        assertNotNull(nameInfo);
        System.out.println("name info size: " + nameInfo.size());
        System.out.println("Name: " + nameInfo.getString("name", ""));
        System.out.println("Description: " + nameInfo.getString("description", ""));
        System.out.println("URL: " + nameInfo.getString("url", ""));
        assertEquals(unsTestName, nameInfo.getString("name", ""));

        unsContractBytes = testSpace.client.queryNameContract(unsTestName);
        System.out.println("unsContractBytes: " + unsContractBytes);
        assertEquals(true, Arrays.equals(unsContract2.getPackedTransaction(), unsContractBytes));

        testSpace.nodes.forEach(x -> x.shutdown());

    }

    @Test
    public void testRevocationContractsApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> issuerPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));

        Set<PublicKey> ownerPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(2)));

        Contract sourceContract = ContractsService.createShareContract(issuerPrivateKeys,ownerPublicKeys,"100");

        sourceContract.check();
        sourceContract.traceErrors();

        ItemResult itemResult = client.register(sourceContract.getPackedTransaction(), 5000);
        System.out.println("sourceContract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, client.getState(sourceContract.getId()).state);

        Contract revokeContract = ContractsService.createRevocation(sourceContract, TestKeys.privateKey(1));

        revokeContract.check();
        revokeContract.traceErrors();

        itemResult = client.register(revokeContract.getPackedTransaction(), 5000);
        System.out.println("revokeContract itemResult: " + itemResult);

        assertEquals(ItemState.APPROVED, client.getState(revokeContract.getId()).state);
        assertEquals(ItemState.REVOKED, client.getState(sourceContract.getId()).state);

        mm.forEach(x -> x.shutdown());

     }

    @Test
    public void testSwapContractsApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> martyPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));
        Set<PrivateKey> stepaPrivateKeys = new HashSet<>(Arrays.asList(TestKeys.privateKey(2)));
        Set<PublicKey> martyPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(1)));
        Set<PublicKey> stepaPublicKeys = new HashSet<>(Arrays.asList(TestKeys.publicKey(2)));


        Contract delorean = ContractsService.createTokenContract(martyPrivateKeys, martyPublicKeys, "100", 0.0001);
        delorean.seal();

        delorean.check();
        delorean.traceErrors();

        ItemResult itemResult = client.register(delorean.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, itemResult.state);

        Contract lamborghini = ContractsService.createTokenContract(stepaPrivateKeys, stepaPublicKeys, "100", 0.0001);
        lamborghini.seal();

        lamborghini.check();
        lamborghini.traceErrors();

        itemResult = client.register(lamborghini.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, itemResult.state);


        // register swapped contracts using ContractsService
        System.out.println("--- register swapped contracts using ContractsService ---");

        Contract swapContract;

        // first Marty create transaction, add both contracts and swap owners, sign own new contract
        swapContract = ContractsService.startSwap(delorean, lamborghini, martyPrivateKeys, stepaPublicKeys);

        // then Marty send new revisions to Stepa
        // and Stepa sign own new contract, Marty's new contract
        swapContract = Contract.fromPackedTransaction(swapContract.getPackedTransaction());
        ContractsService.signPresentedSwap(swapContract, stepaPrivateKeys);

        // then Stepa send draft transaction back to Marty
        // and Marty sign Stepa's new contract and send to approving
        swapContract = Contract.fromPackedTransaction(swapContract.getPackedTransaction());
        ContractsService.finishSwap(swapContract, martyPrivateKeys);

        swapContract.check();
        swapContract.traceErrors();
        System.out.println("Transaction contract for swapping is valid: " + swapContract.isOk());

        itemResult = client.register(swapContract.getPackedTransaction(), 10000);
        assertEquals(ItemState.APPROVED, itemResult.state);


        // check old revisions for ownership contracts
        System.out.println("--- check old revisions for ownership contracts ---");

        ItemResult deloreanResult = main.node.waitItem(delorean.getId(), 5000);
        System.out.println("DeLorean revoked ownership contract revision " + delorean.getRevision() + " is " + deloreanResult + " by Network");
        System.out.println("DeLorean revoked ownership was belongs to Marty: " + delorean.getOwner().isAllowedForKeys(martyPublicKeys));
        assertEquals(ItemState.REVOKED, deloreanResult.state);

        ItemResult lamborghiniResult = main.node.waitItem(lamborghini.getId(), 5000);
        System.out.println("Lamborghini revoked ownership contract revision " + lamborghini.getRevision() + " is " + lamborghiniResult + " by Network");
        System.out.println("Lamborghini revoked ownership was belongs to Stepa: " + lamborghini.getOwner().isAllowedForKeys(stepaPublicKeys));
        assertEquals(ItemState.REVOKED, lamborghiniResult.state);

        // check new revisions for ownership contracts
        System.out.println("--- check new revisions for ownership contracts ---");

        Contract newDelorean = null;
        Contract newLamborghini = null;
        for (Contract c : swapContract.getNew()) {
            if(c.getParent().equals(delorean.getId())) {
                newDelorean = c;
            }
            if(c.getParent().equals(lamborghini.getId())) {
                newLamborghini = c;
            }
        }

        deloreanResult = main.node.waitItem(newDelorean.getId(), 5000);
        System.out.println("DeLorean ownership contract revision " + newDelorean.getRevision() + " is " + deloreanResult + " by Network");
        System.out.println("DeLorean ownership is now belongs to Stepa: " + newDelorean.getOwner().isAllowedForKeys(stepaPublicKeys));
        assertEquals(ItemState.APPROVED, deloreanResult.state);
        assertTrue(newDelorean.getOwner().isAllowedForKeys(stepaPublicKeys));

        lamborghiniResult = main.node.waitItem(newLamborghini.getId(), 5000);
        System.out.println("Lamborghini ownership contract revision " + newLamborghini.getRevision() + " is " + lamborghiniResult + " by Network");
        System.out.println("Lamborghini ownership is now belongs to Marty: " + newLamborghini.getOwner().isAllowedForKeys(martyPublicKeys));
        assertEquals(ItemState.APPROVED, lamborghiniResult.state);
        assertTrue(newLamborghini.getOwner().isAllowedForKeys(martyPublicKeys));

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testSwapSplitJoin_Api2() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> user1PrivateKeySet = new HashSet<>(Arrays.asList(TestKeys.privateKey(1)));
        Set<PrivateKey> user2PrivateKeySet = new HashSet<>(Arrays.asList(TestKeys.privateKey(2)));
        Set<PublicKey> user1PublicKeySet = user1PrivateKeySet.stream().map(prv -> prv.getPublicKey()).collect(Collectors.toSet());
        Set<PublicKey> user2PublicKeySet = user2PrivateKeySet.stream().map(prv -> prv.getPublicKey()).collect(Collectors.toSet());


        Contract contractTOK92 = ContractsService.createTokenContract(user1PrivateKeySet, user1PublicKeySet, "100", 0.0001);
        Contract contractTOK93 = ContractsService.createTokenContract(user2PrivateKeySet, user2PublicKeySet, "100", 0.001);

        contractTOK92.seal();
        contractTOK92.check();
        contractTOK92.traceErrors();

        ItemResult itemResult = client.register(contractTOK92.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, itemResult.state);


        contractTOK93.seal();
        contractTOK93.check();
        contractTOK93.traceErrors();

        itemResult = client.register(contractTOK93.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, itemResult.state);

        System.out.println("--- tokens created ---");

        // TOK92: 100 - 8.02 = 91.98
        Contract user1CoinsSplit = ContractsService.createSplit(contractTOK92, "8.02", "amount", user1PrivateKeySet);
        Contract user1CoinsSplitToUser2 = user1CoinsSplit.getNew().get(0);
        // TOK93: 100 - 10.01 = 89.99
        Contract user2CoinsSplit = ContractsService.createSplit(contractTOK93, "10.01", "amount", user2PrivateKeySet);
        Contract user2CoinsSplitToUser1 = user2CoinsSplit.getNew().get(0);

        user1CoinsSplitToUser2.check();
        user1CoinsSplitToUser2.traceErrors();
        user2CoinsSplitToUser1.check();
        user2CoinsSplitToUser1.traceErrors();

        // exchanging the contracts

        System.out.println("--- procedure for exchange of contracts ---");

        // Step one

        Contract swapContract;
        swapContract = ContractsService.startSwap(user1CoinsSplitToUser2, user2CoinsSplitToUser1, user1PrivateKeySet, user2PublicKeySet, false);

        // Step two

        ContractsService.signPresentedSwap(swapContract, user2PrivateKeySet);

        // Final step

        ContractsService.finishSwap(swapContract, user1PrivateKeySet);

        user1CoinsSplit.seal();
        user2CoinsSplit.seal();
        swapContract.getNewItems().clear();
        swapContract.addNewItems(user1CoinsSplit, user2CoinsSplit);
        swapContract.seal();

        swapContract.check();
        swapContract.traceErrors();
        System.out.println("Transaction contract for swapping is valid: " + swapContract.isOk());

        //now emulate sending transaction pack through network

        swapContract = Contract.fromPackedTransaction(swapContract.getPackedTransaction());

        main.node.registerItem(swapContract);

        assertEquals(ItemState.APPROVED, main.node.waitItem(swapContract.getId(), 5000).state);

        assertEquals(ItemState.APPROVED, main.node.waitItem(user1CoinsSplit.getId(), 5000).state);
        assertEquals(ItemState.APPROVED, main.node.waitItem(user2CoinsSplit.getId(), 5000).state);
        assertEquals(ItemState.APPROVED, main.node.waitItem(user1CoinsSplitToUser2.getId(), 5000).state);
        assertEquals(ItemState.APPROVED, main.node.waitItem(user2CoinsSplitToUser1.getId(), 5000).state);
        assertEquals(ItemState.REVOKED, main.node.waitItem(contractTOK92.getId(), 5000).state);
        assertEquals(ItemState.REVOKED, main.node.waitItem(contractTOK93.getId(), 5000).state);
        assertEquals("8.02", user1CoinsSplitToUser2.getStateData().getStringOrThrow("amount"));
        assertEquals("10.01", user2CoinsSplitToUser1.getStateData().getStringOrThrow("amount"));
        assertFalse(user1CoinsSplitToUser2.getOwner().isAllowedForKeys(user1PublicKeySet));
        assertTrue(user1CoinsSplitToUser2.getOwner().isAllowedForKeys(user2PublicKeySet));
        assertTrue(user2CoinsSplitToUser1.getOwner().isAllowedForKeys(user1PublicKeySet));
        assertFalse(user2CoinsSplitToUser1.getOwner().isAllowedForKeys(user2PublicKeySet));

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void testAddReferenceApi() throws Exception {

        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);

        Set<PrivateKey> stepaPrivateKeys = new HashSet<>();//manager -
        Set<PrivateKey>  llcPrivateKeys = new HashSet<>(); //issuer
        Set<PrivateKey>  thirdPartyPrivateKeys = new HashSet<>();

        llcPrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "_xer0yfe2nn1xthc.private.unikey")));
        stepaPrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "keys/stepan_mamontov.private.unikey")));
        thirdPartyPrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "keys/marty_mcfly.private.unikey")));

        Set<PublicKey> stepaPublicKeys = new HashSet<>();
        for (PrivateKey pk : stepaPrivateKeys) {
            stepaPublicKeys.add(pk.getPublicKey());
        }
        Set<PublicKey> thirdPartyPublicKeys = new HashSet<>();
        for (PrivateKey pk : thirdPartyPrivateKeys) {
            thirdPartyPublicKeys.add(pk.getPublicKey());
        }

        Contract jobCertificate = new Contract(llcPrivateKeys.iterator().next());
        jobCertificate.setOwnerKeys(stepaPublicKeys);
        jobCertificate.getDefinition().getData().set("issuer", "ApricoT");
        jobCertificate.getDefinition().getData().set("type", "chief accountant assignment");
        jobCertificate.seal();

        jobCertificate.check();
        jobCertificate.traceErrors();

        ItemResult itemResult = client.register(jobCertificate.getPackedTransaction(), 5000);
        System.out.println("sourceContract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, client.getState(jobCertificate.getId()).state);

        Contract llcProperty = ContractsService.createNotaryContract(llcPrivateKeys, stepaPublicKeys);

        List <String> listConditions = new ArrayList<>();
        listConditions.add("ref.definition.issuer == \"HggcAQABxAACzHE9ibWlnK4RzpgFIB4jIg3WcXZSKXNAqOTYUtGXY03xJSwpqE+y/HbqqE0WsmcAt5\n" +
                "          a0F5H7bz87Uy8Me1UdIDcOJgP8HMF2M0I/kkT6d59ZhYH/TlpDcpLvnJWElZAfOytaICE01bkOkf6M\n" +
                "          z5egpToDEEPZH/RXigj9wkSXkk43WZSxVY5f2zaVmibUZ9VLoJlmjNTZ+utJUZi66iu9e0SXupOr/+\n" +
                "          BJL1Gm595w32Fd0141kBvAHYDHz2K3x4m1oFAcElJ83ahSl1u85/naIaf2yuxiQNz3uFMTn0IpULCM\n" +
                "          vLMvmE+L9io7+KWXld2usujMXI1ycDRw85h6IJlPcKHVQKnJ/4wNBUveBDLFLlOcMpCzWlO/D7M2Iy\n" +
                "          Na8XEvwPaFJlN1UN/9eVpaRUBEfDq6zi+RC8MaVWzFbNi913suY0Q8F7ejKR6aQvQPuNN6bK6iRYZc\n" +
                "          hxe/FwWIXOr0C0yA3NFgxKLiKZjkd5eJ84GLy+iD00Rzjom+GG4FDQKr2HxYZDdDuLE4PEpYSzEB/8\n" +
                "          LyIqeM7dSyaHFTBII/sLuFru6ffoKxBNk/cwAGZqOwD3fkJjNq1R3h6QylWXI/cSO9yRnRMmMBJwal\n" +
                "          MexOc3/kPEEdfjH/GcJU0Mw6DgoY8QgfaNwXcFbBUvf3TwZ5Mysf21OLHH13g8gzREm+h8c=\"");
        listConditions.add("ref.definition.data.issuer == \"ApricoT\"");
        listConditions.add("ref.definition.data.type == \"chief accountant assignment\"");

        ContractsService.addReferenceToContract(llcProperty, jobCertificate, "certification_contract", Reference.TYPE_EXISTING_DEFINITION, listConditions, true);

        llcProperty.check();
        llcProperty.traceErrors();

        itemResult = client.register(llcProperty.getPackedTransaction(), 5000);
        System.out.println("sourceContract itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, client.getState(llcProperty.getId()).state);

        mm.forEach(x -> x.shutdown());

    }

    @Test
    public void paymentTest1() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));
        Main main = mm.get(0);
        main.config.setIsFreeRegistrationsAllowedFromYaml(true);
        Client client = new Client(TestKeys.privateKey(20), main.myInfo, null);


        Contract simpleContract = new Contract(TestKeys.privateKey(1));
        simpleContract.seal();

        Contract stepaU = InnerContractsService.createFreshU(100000000, new HashSet<>(Arrays.asList(TestKeys.publicKey(1))));
        ItemResult itemResult = client.register(stepaU.getPackedTransaction(), 5000);
        System.out.println("stepaU itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);
        main.config.setIsFreeRegistrationsAllowedFromYaml(false);

        Parcel parcel = ContractsService.createParcel(simpleContract, stepaU, 1, new HashSet<>(Arrays.asList(TestKeys.privateKey(1))), false);
        client.registerParcel(parcel.pack(), 5000);
        assertEquals(ItemState.APPROVED, client.getState(simpleContract.getId()).state);

        mm.forEach(x -> x.shutdown());

    }
    protected static final String ROOT_PATH = "./src/test_contracts/";


    protected Contract getApprovedUContract(TestSpace testSpace) throws Exception {
        synchronized (testSpace.uContractLock) {
            if (testSpace.uContract == null) {

                Set<PublicKey> keys = new HashSet();
                keys.add(testSpace.myKey.getPublicKey());
                Contract stepaU = InnerContractsService.createFreshU(100000000, keys);
                stepaU.check();
                stepaU.traceErrors();
                System.out.println("register new U ");
                testSpace.node.node.registerItem(stepaU);
                testSpace.uContract = stepaU;
            }
            int needRecreateUContractNum = 0;
            for (Main m : testSpace.nodes) {
                try {
                    ItemResult itemResult = m.node.waitItem(testSpace.uContract.getId(), 15000);
                    //assertEquals(ItemState.APPROVED, itemResult.state);
                    if (itemResult.state != ItemState.APPROVED) {
                        System.out.println("U: node " + m.node + " result: " + itemResult);
                        needRecreateUContractNum ++;
                    }
                } catch (TimeoutException e) {
                    System.out.println("ping ");
//                    System.out.println(n.ping());
////                    System.out.println(n.traceTasksPool());
//                    System.out.println(n.traceParcelProcessors());
//                    System.out.println(n.traceItemProcessors());
                    System.out.println("U: node " + m.node + " timeout: ");
                    needRecreateUContractNum ++;
                }
            }
            int recreateBorder = testSpace.nodes.size() - testSpace.node.config.getPositiveConsensus() - 1;
            if(recreateBorder < 0)
                recreateBorder = 0;
            if (needRecreateUContractNum > recreateBorder) {
                testSpace.uContract = null;
                Thread.sleep(1000);
                return getApprovedUContract(testSpace);
            }
            return testSpace.uContract;
        }
    }

    private NSmartContract.NodeInfoProvider nodeInfoProvider = new NSmartContract.NodeInfoProvider() {
        Config config = new Config();
        @Override
        public Set<KeyAddress> getUIssuerKeys() {
            return config.getUIssuerKeys();
        }

        @Override
        public String getUIssuerName() {
            return config.getUIssuerName();
        }

        @Override
        public int getMinPayment(String extendedType) {
            return config.getMinPayment(extendedType);
        }

        @Override
        public double getRate(String extendedType) {
            return config.getRate(extendedType);
        }

        @Override
        public Collection<PublicKey> getAdditionalKeysToSignWith(String extendedType) {
            Set<PublicKey> set = new HashSet<>();
            if(extendedType.equals(NSmartContract.SmartContractType.UNS1)) {
                set.add(config.getAuthorizedNameServiceCenterKey());
            }
            return set;
        }
    };

    @Test(timeout = 90000)
    public void checkUnsNodeMissedRevocation() throws Exception {


        PrivateKey randomPrivKey1 = new PrivateKey(2048);
        PrivateKey randomPrivKey2 = new PrivateKey(2048);
        PrivateKey randomPrivKey3 = new PrivateKey(2048);
        PrivateKey randomPrivKey4 = new PrivateKey(2048);


        Set<PrivateKey> manufacturePrivateKeys = new HashSet<>();
        manufacturePrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "_xer0yfe2nn1xthc.private.unikey")));

        TestSpace testSpace = prepareTestSpace(manufacturePrivateKeys.iterator().next());

        PrivateKey authorizedNameServiceKey = TestKeys.privateKey(3);
        testSpace.nodes.forEach( m -> m.config.setAuthorizedNameServiceCenterKeyData(new Bytes(authorizedNameServiceKey.getPublicKey().pack())));

        String name = "test"+Instant.now().getEpochSecond();


        UnsContract uns = new UnsContract(manufacturePrivateKeys.iterator().next());
        uns.addSignerKey(authorizedNameServiceKey);

        UnsName unsName = new UnsName(name, "test description", "http://test.com");
        unsName.setUnsReducedName(name);
        UnsRecord unsRecord = new UnsRecord(randomPrivKey1.getPublicKey());
        unsName.addUnsRecord(unsRecord);
        uns.addUnsName(unsName);

        uns.setNodeInfoProvider(nodeInfoProvider);
        uns.seal();
        uns.addSignatureToSeal(randomPrivKey1);
        uns.addSignatureToSeal(TestKeys.privateKey(8));
        uns.check();
        uns.traceErrors();


        UnsContract uns2 = new UnsContract(manufacturePrivateKeys.iterator().next());
        uns2.addSignerKey(authorizedNameServiceKey);

        UnsName unsName2 = new UnsName( name, "test description", "http://test.com");
        unsName2.setUnsReducedName(name);
        UnsRecord unsRecord2 = new UnsRecord(randomPrivKey2.getPublicKey());
        unsName2.addUnsRecord(unsRecord2);
        uns2.addUnsName(unsName2);

        uns2.setNodeInfoProvider(nodeInfoProvider);
        uns2.seal();
        uns2.addSignatureToSeal(randomPrivKey2);
        uns2.addSignatureToSeal(TestKeys.privateKey(8));
        uns2.check();
        uns2.traceErrors();

        UnsContract uns3 = new UnsContract(manufacturePrivateKeys.iterator().next());
        uns3.addSignerKey(authorizedNameServiceKey);

        UnsName unsName3 = new UnsName( name, "test description", "http://test.com");
        unsName3.setUnsReducedName(name);
        UnsRecord unsRecord3 = new UnsRecord(randomPrivKey3.getPublicKey());
        unsName3.addUnsRecord(unsRecord3);
        uns3.addUnsName(unsName3);

        uns3.setNodeInfoProvider(nodeInfoProvider);
        uns3.seal();
        uns3.addSignatureToSeal(randomPrivKey3);
        uns3.addSignatureToSeal(TestKeys.privateKey(8));
        uns3.check();
        uns3.traceErrors();

        //REGISTER UNS1
        Contract paymentContract = getApprovedUContract(testSpace);


        Parcel payingParcel = ContractsService.createPayingParcel(uns.getTransactionPack(), paymentContract, 1, nodeInfoProvider.getMinPayment("UNS1"), manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(payingParcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // wait parcel
        testSpace.node.node.waitParcel(payingParcel.getId(), 8000);
        // check payment and payload contracts
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(payingParcel.getPayload().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(payingParcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(uns.getNew().get(0).getId(), 8000).state);

        assertEquals(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()).getEntries().size(),1);


        //REVOKE UNS1
        Contract revokingContract = new Contract(manufacturePrivateKeys.iterator().next());
        revokingContract.addRevokingItems(uns);
        revokingContract.seal();

        paymentContract = getApprovedUContract(testSpace);
        Parcel parcel = ContractsService.createParcel(revokingContract.getTransactionPack(), paymentContract, 1, manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(parcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = parcel.getPaymentContract();
        }
        // wait parcel
        testSpace.node.node.waitParcel(parcel.getId(), 8000);

        ItemResult ir = testSpace.node.node.waitItem(parcel.getPayload().getContract().getId(), 8000);
        assertEquals(ItemState.APPROVED, ir.state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(parcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(uns.getId(), 8000).state);

        assertNull(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()));

        //REGISTER UNS2
        paymentContract = getApprovedUContract(testSpace);
        payingParcel = ContractsService.createPayingParcel(uns2.getTransactionPack(), paymentContract, 1, nodeInfoProvider.getMinPayment("UNS1"), manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(payingParcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // wait parcel
        testSpace.node.node.waitParcel(payingParcel.getId(), 8000);
        // check payment and payload contracts
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(payingParcel.getPayload().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(payingParcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(uns2.getNew().get(0).getId(), 8000).state);

        assertEquals(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()).getEntries().size(),1);

        //SHUTDOWN LAST NODE
        testSpace.nodes.remove(testSpace.nodes.size()-1).shutdown();
        Thread.sleep(4000);

        //REVOKE UNS2
        revokingContract = new Contract(manufacturePrivateKeys.iterator().next());
        revokingContract.addRevokingItems(uns2);
        revokingContract.seal();

        paymentContract = getApprovedUContract(testSpace);
        parcel = ContractsService.createParcel(revokingContract.getTransactionPack(), paymentContract, 1, manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(parcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = parcel.getPaymentContract();
        }
        // wait parcel
        testSpace.node.node.waitParcel(parcel.getId(), 8000);

        ir = testSpace.node.node.waitItem(parcel.getPayload().getContract().getId(), 8000);
        assertEquals(ItemState.APPROVED, ir.state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(parcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(uns2.getId(), 8000).state);


        assertNull(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()));
        //RECREATE NODES
        testSpace.nodes.forEach(m->m.shutdown());
        Thread.sleep(4000);
        testSpace = prepareTestSpace(manufacturePrivateKeys.iterator().next());
        testSpace.nodes.forEach( m -> m.config.setAuthorizedNameServiceCenterKeyData(new Bytes(authorizedNameServiceKey.getPublicKey().pack())));

        assertNull(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()));
        //LAST NODE MISSED UNS2 REVOKE
        assertNotNull(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(unsName.getUnsName()));

        //REGISTER UNS3
        paymentContract = getApprovedUContract(testSpace);

        payingParcel = ContractsService.createPayingParcel(uns3.getTransactionPack(), paymentContract, 1, nodeInfoProvider.getMinPayment("UNS1"), manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(payingParcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // wait parcel
        testSpace.node.node.waitParcel(payingParcel.getId(), 8000);
        // check payment and payload contracts
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(payingParcel.getPayload().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(payingParcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(uns3.getNew().get(0).getId(), 8000).state);

        NNameRecord nrm = testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName());
        NNameRecord nrmLast = testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(unsName.getUnsName());
        assertEquals(nrm.getEntries().size(),1);
        assertEquals(nrmLast.getEntries().size(),1);
        assertNotEquals(nrm.getEntries().iterator().next().getShortAddress(),nrmLast.getEntries().iterator().next().getShortAddress());
        assertNotEquals(nrm.getEntries().iterator().next().getLongAddress(),nrmLast.getEntries().iterator().next().getLongAddress());

        Thread.sleep(4000);

        nrmLast = testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(unsName.getUnsName());

        assertEquals(nrm.getEntries().size(),1);
        assertEquals(nrmLast.getEntries().size(),1);
        assertEquals(nrm.getEntries().iterator().next().getShortAddress(),nrmLast.getEntries().iterator().next().getShortAddress());
        assertEquals(nrm.getEntries().iterator().next().getLongAddress(),nrmLast.getEntries().iterator().next().getLongAddress());

        testSpace.nodes.forEach(m->m.shutdown());

    }


    @Test(timeout = 90000)
    public void checkUnsNodeMissedRevision() throws Exception {


        PrivateKey randomPrivKey1 = new PrivateKey(2048);
        PrivateKey randomPrivKey2 = new PrivateKey(2048);
        PrivateKey randomPrivKey3 = new PrivateKey(2048);
        PrivateKey randomPrivKey4 = new PrivateKey(2048);


        Set<PrivateKey> manufacturePrivateKeys = new HashSet<>();
        manufacturePrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "_xer0yfe2nn1xthc.private.unikey")));

        TestSpace testSpace = prepareTestSpace(manufacturePrivateKeys.iterator().next());

        PrivateKey authorizedNameServiceKey = TestKeys.privateKey(3);
        testSpace.nodes.forEach( m -> m.config.setAuthorizedNameServiceCenterKeyData(new Bytes(authorizedNameServiceKey.getPublicKey().pack())));

        String name = "test"+Instant.now().getEpochSecond();


        UnsContract uns = new UnsContract(manufacturePrivateKeys.iterator().next());
        uns.addSignerKey(authorizedNameServiceKey);

        UnsName unsName = new UnsName(name, "test description", "http://test.com");
        unsName.setUnsReducedName(name);
        UnsRecord unsRecord = new UnsRecord(randomPrivKey1.getPublicKey());
        unsName.addUnsRecord(unsRecord);
        uns.addUnsName(unsName);

        uns.setNodeInfoProvider(nodeInfoProvider);
        uns.seal();
        uns.addSignatureToSeal(randomPrivKey1);
        uns.addSignatureToSeal(TestKeys.privateKey(8));
        uns.check();
        uns.traceErrors();


        UnsContract uns2 = new UnsContract(manufacturePrivateKeys.iterator().next());
        uns2.addSignerKey(authorizedNameServiceKey);

        UnsName unsName2 = new UnsName(name, "test description", "http://test.com");
        unsName2.setUnsReducedName(name);
        UnsRecord unsRecord2 = new UnsRecord(randomPrivKey2.getPublicKey());
        unsName2.addUnsRecord(unsRecord2);
        uns2.addUnsName(unsName2);

        uns2.setNodeInfoProvider(nodeInfoProvider);
        uns2.seal();
        uns2.addSignatureToSeal(randomPrivKey2);
        uns2.addSignatureToSeal(TestKeys.privateKey(8));
        uns2.check();
        uns2.traceErrors();

        UnsContract uns3 = new UnsContract(manufacturePrivateKeys.iterator().next());
        uns3.addSignerKey(authorizedNameServiceKey);

        UnsName unsName3 = new UnsName(name, "test description", "http://test.com");
        unsName3.setUnsReducedName(name);
        UnsRecord unsRecord3 = new UnsRecord(randomPrivKey3.getPublicKey());
        unsName3.addUnsRecord(unsRecord3);
        uns3.addUnsName(unsName3);

        uns3.setNodeInfoProvider(nodeInfoProvider);
        uns3.seal();
        uns3.addSignatureToSeal(randomPrivKey3);
        uns3.addSignatureToSeal(TestKeys.privateKey(8));
        uns3.check();
        uns3.traceErrors();

        //REGISTER UNS1
        Contract paymentContract = getApprovedUContract(testSpace);


        Parcel payingParcel = ContractsService.createPayingParcel(uns.getTransactionPack(), paymentContract, 1, nodeInfoProvider.getMinPayment("UNS1"), manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(payingParcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // wait parcel
        testSpace.node.node.waitParcel(payingParcel.getId(), 8000);
        // check payment and payload contracts
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(payingParcel.getPayload().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(payingParcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(uns.getNew().get(0).getId(), 8000).state);

        assertEquals(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()).getEntries().size(),1);


        //REVOKE UNS1
        Contract revokingContract = new Contract(manufacturePrivateKeys.iterator().next());
        revokingContract.addRevokingItems(uns);
        revokingContract.seal();

        paymentContract = getApprovedUContract(testSpace);
        Parcel parcel = ContractsService.createParcel(revokingContract.getTransactionPack(), paymentContract, 1, manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(parcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = parcel.getPaymentContract();
        }
        // wait parcel
        testSpace.node.node.waitParcel(parcel.getId(), 8000);

        ItemResult ir = testSpace.node.node.waitItem(parcel.getPayload().getContract().getId(), 8000);
        assertEquals(ItemState.APPROVED, ir.state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(parcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(uns.getId(), 8000).state);

        assertNull(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()));

        //REGISTER UNS2
        paymentContract = getApprovedUContract(testSpace);
        payingParcel = ContractsService.createPayingParcel(uns2.getTransactionPack(), paymentContract, 1, nodeInfoProvider.getMinPayment("UNS1"), manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(payingParcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // wait parcel
        testSpace.node.node.waitParcel(payingParcel.getId(), 8000);
        // check payment and payload contracts
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(payingParcel.getPayload().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(payingParcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(uns2.getNew().get(0).getId(), 8000).state);

        assertEquals(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()).getEntries().size(),1);

        //SHUTDOWN LAST NODE
        testSpace.nodes.remove(testSpace.nodes.size()-1).shutdown();
        Thread.sleep(4000);

        //UPDATE UNS2

        Set<PrivateKey> keys = new HashSet<>();
        keys.add(TestKeys.privateKey(2));
        keys.add(randomPrivKey4);
        keys.add(manufacturePrivateKeys.iterator().next());
        keys.add(authorizedNameServiceKey);

        uns2 = (UnsContract) uns2.createRevision(keys);
        uns2.removeName(name);
        UnsName unsName2_1 = new UnsName(name+"2", "test description", "http://test.com");
        unsName2_1.setUnsReducedName(name+"2");
        UnsRecord unsRecord2_1 = new UnsRecord(randomPrivKey4.getPublicKey());
        unsName2_1.addUnsRecord(unsRecord2_1);
        uns2.addUnsName(unsName2_1);

        uns2.setNodeInfoProvider(nodeInfoProvider);
        uns2.seal();

        parcel = ContractsService.createParcel(uns2,getApprovedUContract(testSpace),1,manufacturePrivateKeys);
        testSpace.node.node.registerParcel(parcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = parcel.getPaymentContract();
        }
        // wait parcel
        testSpace.node.node.waitParcel(parcel.getId(), 8000);

        ir = testSpace.node.node.waitItem(uns2.getId(), 8000);
        assertEquals(ItemState.APPROVED, ir.state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(parcel.getPayment().getContract().getId(), 8000).state);

        assertNull(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()));

        //RECREATE NODES
        testSpace.nodes.forEach(m->m.shutdown());
        Thread.sleep(4000);
        testSpace = prepareTestSpace(manufacturePrivateKeys.iterator().next());
        testSpace.nodes.forEach( m -> m.config.setAuthorizedNameServiceCenterKeyData(new Bytes(authorizedNameServiceKey.getPublicKey().pack())));

        assertNull(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()));
        //LAST NODE MISSED UNS2 REVISION
        assertNotNull(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(unsName.getUnsName()));

        //REGISTER UNS3
        paymentContract = getApprovedUContract(testSpace);

        payingParcel = ContractsService.createPayingParcel(uns3.getTransactionPack(), paymentContract, 1, nodeInfoProvider.getMinPayment("UNS1"), manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(payingParcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // wait parcel
        testSpace.node.node.waitParcel(payingParcel.getId(), 8000);
        // check payment and payload contracts
        ir = testSpace.node.node.waitItem(payingParcel.getPayload().getContract().getId(), 8000);
        assertEquals(ItemState.APPROVED, ir.state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(payingParcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(uns3.getNew().get(0).getId(), 8000).state);

        NNameRecord nrm = testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName());
        NNameRecord nrmLast = testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(unsName.getUnsName());
        assertEquals(nrm.getEntries().size(),1);
        assertEquals(nrmLast.getEntries().size(),1);
        assertNotEquals(nrm.getEntries().iterator().next().getShortAddress(),nrmLast.getEntries().iterator().next().getShortAddress());
        assertNotEquals(nrm.getEntries().iterator().next().getLongAddress(),nrmLast.getEntries().iterator().next().getLongAddress());

        Thread.sleep(4000);

        nrmLast = testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(unsName.getUnsName());

        assertEquals(nrm.getEntries().size(),1);
        assertEquals(nrmLast.getEntries().size(),1);
        assertEquals(nrm.getEntries().iterator().next().getShortAddress(),nrmLast.getEntries().iterator().next().getShortAddress());
        assertEquals(nrm.getEntries().iterator().next().getLongAddress(),nrmLast.getEntries().iterator().next().getLongAddress());

        testSpace.nodes.forEach(m->m.shutdown());

    }


    @Test(timeout = 90000)
    public void checkUnsNodeMissedSelfRevision() throws Exception {


        PrivateKey randomPrivKey1 = new PrivateKey(2048);
        PrivateKey randomPrivKey2 = new PrivateKey(2048);
        PrivateKey randomPrivKey3 = new PrivateKey(2048);


        Set<PrivateKey> manufacturePrivateKeys = new HashSet<>();
        manufacturePrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "_xer0yfe2nn1xthc.private.unikey")));

        TestSpace testSpace = prepareTestSpace(manufacturePrivateKeys.iterator().next());

        PrivateKey authorizedNameServiceKey = TestKeys.privateKey(3);
        testSpace.nodes.forEach( m -> m.config.setAuthorizedNameServiceCenterKeyData(new Bytes(authorizedNameServiceKey.getPublicKey().pack())));

        String name = "test"+Instant.now().getEpochSecond();
        String name2 = "test2"+Instant.now().getEpochSecond();


        UnsContract uns = new UnsContract(manufacturePrivateKeys.iterator().next());
        uns.addSignerKey(authorizedNameServiceKey);

        UnsName unsName = new UnsName(name, "test description", "http://test.com");
        unsName.setUnsReducedName(name);
        UnsRecord unsRecord = new UnsRecord(randomPrivKey1.getPublicKey());
        unsName.addUnsRecord(unsRecord);
        uns.addUnsName(unsName);

        uns.setNodeInfoProvider(nodeInfoProvider);
        uns.seal();
        uns.addSignatureToSeal(randomPrivKey1);
        uns.addSignatureToSeal(TestKeys.privateKey(8));
        uns.check();
        uns.traceErrors();



        //REGISTER UNS1
        Contract paymentContract = getApprovedUContract(testSpace);


        Parcel payingParcel = ContractsService.createPayingParcel(uns.getTransactionPack(), paymentContract, 1, nodeInfoProvider.getMinPayment("UNS1"), manufacturePrivateKeys, false);

        testSpace.node.node.registerParcel(payingParcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // wait parcel
        testSpace.node.node.waitParcel(payingParcel.getId(), 8000);
        // check payment and payload contracts
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(payingParcel.getPayload().getContract().getId(), 8000).state);
        assertEquals(ItemState.REVOKED, testSpace.node.node.waitItem(payingParcel.getPayment().getContract().getId(), 8000).state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(uns.getNew().get(0).getId(), 8000).state);

        assertEquals(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()).getEntries().size(),1);


        //SHUTDOWN LAST NODE
        testSpace.nodes.remove(testSpace.nodes.size()-1).shutdown();
        Thread.sleep(4000);

        //UPDATE UNS

        Set<PrivateKey> keys = new HashSet<>();
        keys.add(TestKeys.privateKey(2));
        keys.add(randomPrivKey2);
        keys.add(manufacturePrivateKeys.iterator().next());
        keys.add(authorizedNameServiceKey);

        uns = (UnsContract) uns.createRevision(keys);
        uns.removeName(name);
        UnsName unsName2 = new UnsName(name2, "test description", "http://test.com");
        unsName2.setUnsReducedName(name2);
        UnsRecord unsRecord2 = new UnsRecord(randomPrivKey2.getPublicKey());
        unsName2.addUnsRecord(unsRecord2);
        uns.addUnsName(unsName2);

        uns.setNodeInfoProvider(nodeInfoProvider);
        uns.seal();

        Parcel parcel = ContractsService.createParcel(uns, getApprovedUContract(testSpace), 1, manufacturePrivateKeys);
        testSpace.node.node.registerParcel(parcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = parcel.getPaymentContract();
        }
        // wait parcel
        testSpace.node.node.waitParcel(parcel.getId(), 8000);

        ItemResult ir = testSpace.node.node.waitItem(uns.getId(), 8000);
        assertEquals(ItemState.APPROVED, ir.state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(parcel.getPayment().getContract().getId(), 8000).state);

        assertNull(testSpace.node.node.getLedger().getNameRecord(unsName.getUnsName()));

        //RECREATE NODES
        testSpace.nodes.forEach(m->m.shutdown());
        Thread.sleep(4000);
        testSpace = prepareTestSpace(manufacturePrivateKeys.iterator().next());
        testSpace.nodes.forEach( m -> m.config.setAuthorizedNameServiceCenterKeyData(new Bytes(authorizedNameServiceKey.getPublicKey().pack())));

        assertNull(testSpace.node.node.getLedger().getNameRecord(name));
        assertNotNull(testSpace.node.node.getLedger().getNameRecord(name2));
        //LAST NODE MISSED UNS REVISION
        assertNotNull(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(name));
        assertNull(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(name2));

        //REGISTER UNS


        keys = new HashSet<>();
        keys.add(TestKeys.privateKey(2));
        keys.add(randomPrivKey3);
        keys.add(manufacturePrivateKeys.iterator().next());
        keys.add(authorizedNameServiceKey);

        uns = (UnsContract) uns.createRevision(keys);
        uns.removeName(name2);
        UnsName unsName3 = new UnsName(name, "test description", "http://test.com");
        unsName3.setUnsReducedName(name);
        UnsRecord unsRecord3 = new UnsRecord(randomPrivKey3.getPublicKey());
        unsName3.addUnsRecord(unsRecord3);
        uns.addUnsName(unsName3);

        uns.setNodeInfoProvider(nodeInfoProvider);
        uns.seal();

        parcel = ContractsService.createParcel(uns, getApprovedUContract(testSpace), 1, manufacturePrivateKeys);
        testSpace.node.node.registerParcel(parcel);
        synchronized (testSpace.uContractLock) {
            testSpace.uContract = parcel.getPaymentContract();
        }
        // wait parcel
        testSpace.node.node.waitParcel(parcel.getId(), 8000);

        ir = testSpace.node.node.waitItem(uns.getId(), 8000);
        assertEquals(ItemState.APPROVED, ir.state);
        assertEquals(ItemState.APPROVED, testSpace.node.node.waitItem(parcel.getPayment().getContract().getId(), 8000).state);


        KeyAddress long1 = unsRecord.getAddresses().get(0).isLong() ? unsRecord.getAddresses().get(0) : unsRecord.getAddresses().get(1);
        KeyAddress long3 = unsRecord3.getAddresses().get(0).isLong() ? unsRecord3.getAddresses().get(0) : unsRecord3.getAddresses().get(1);


        assertNull(testSpace.node.node.getLedger().getNameRecord(name2));
        assertNotNull(testSpace.node.node.getLedger().getNameRecord(name));
        assertEquals(testSpace.node.node.getLedger().getNameRecord(name).getEntries().iterator().next().getLongAddress(),long3.toString());

        //LAST NODE MISSED UNS REVISION
        assertNotNull(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(name));
        assertNull(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(name2));
        assertEquals(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(name).getEntries().iterator().next().getLongAddress(),long1.toString());

        Thread.sleep(4000);
        assertNotNull(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(name));
        assertNull(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(name2));
        assertEquals(testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger().getNameRecord(name).getEntries().iterator().next().getLongAddress(),long3.toString());



        testSpace.nodes.forEach(m->m.shutdown());

    }

    @Test
    public void environmentSerializationTest() throws Exception{
        UnsName unsName = new UnsName();
        unsName.setUnsName("test");
        unsName.setUnsReducedName("test");

        PrivateKey privateKey = new PrivateKey(2048);
        Contract contract = new Contract(privateKey);
        contract.seal();

        NSmartContract smartContract = new NSmartContract(privateKey);
        smartContract.seal();

        UnsRecord record1 = new UnsRecord(contract.getId());
        UnsRecord record2 = new UnsRecord(privateKey.getPublicKey());

        unsName.addUnsRecord(record1);
        unsName.addUnsRecord(record2);
        ZonedDateTime now = ZonedDateTime.now();
        NNameRecord nnr = new NNameRecord(unsName, now);

        Config.forceInit(NMutableEnvironment.class);


        NNameRecord nnr2 = Boss.load(Boss.pack(nnr));

        assertTrue(nnr2.getEntries().stream().anyMatch(nre -> unsName.getUnsRecords().stream().anyMatch(ur -> ur.equalsTo(nre))));
        assertEquals(nnr2.getEntries().size(),unsName.getRecordsCount());
        assertEquals(nnr2.getName(),unsName.getUnsName());
        assertEquals(nnr2.getNameReduced(),unsName.getUnsReducedName());
        assertEquals(nnr2.getDescription(),unsName.getUnsDescription());
        assertEquals(nnr2.getUrl(),unsName.getUnsURL());
        assertEquals(nnr.expiresAt().toEpochSecond(),nnr2.expiresAt().toEpochSecond());

        NContractStorageSubscription sub = Boss.load(Boss.pack(new NContractStorageSubscription(contract.getPackedTransaction(),now)));
        assertTrue(sub.getContract().getId().equals(contract.getId()));
        assertEquals(sub.expiresAt().toEpochSecond(),now.toEpochSecond());

        Binder kvStore = new Binder();
        kvStore.put("test","test1");
        NImmutableEnvironment environment = new NImmutableEnvironment(smartContract,kvStore,Do.listOf(sub),Do.listOf(nnr2),null);

        environment = Boss.load(Boss.pack(environment));
        assertEquals(environment.get("test",null),"test1");
    }

    @Test
    public void concurrentResyncTest() throws Exception {
        boolean doShutdown = true;
        PrivateKey issuerKey = new PrivateKey(Do.read("./src/test_contracts/keys/reconfig_key.private.unikey"));
        TestSpace testSpace = prepareTestSpace(issuerKey);
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));
        Set<PrivateKey> issuerKeys = new HashSet<>();
        Set<PublicKey> ownerKeys = new HashSet<>();
        issuerKeys.add(issuerKey);
        ownerKeys.add(issuerKey.getPublicKey());


        ArrayList<Contract> contractsToJoin = new ArrayList<>();

        for(int k = 0; k < 4; k++) {
            if (doShutdown) {
                //shutdown one of nodes
                if (k < 3) {
                    int absentNode = k + 1;
                    testSpace.nodes.get(absentNode).shutdown();
                    testSpace.nodes.remove(absentNode);
                }
            }

            Contract contract = new Contract(issuerKey);
            contract.getDefinition().getData().set("test","test1");
            contract.getStateData().set("amount","100");
            Binder params = Binder.of("field_name", "amount", "join_match_fields",asList("definition.issuer"));
            Role ownerLink = new RoleLink("@owner_link","owner");
            contract.registerRole(ownerLink);
            SplitJoinPermission splitJoinPermission = new SplitJoinPermission(ownerLink,params);
            contract.addPermission(splitJoinPermission);
            contract.seal();
            testSpace.client.register(contract.getPackedTransaction(),1500);
            assertEquals(testSpace.client.getState(contract.getId()).state,ItemState.APPROVED);

            if(doShutdown) {
                testSpace.nodes.forEach(n -> n.shutdown());
                Thread.sleep(2000);
                testSpace = prepareTestSpace(issuerKey);
                testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));
            }
            contractsToJoin.add(contract);
        }


        TestSpace finalTestSpace = testSpace;
        contractsToJoin.forEach(c -> {
            int count = 0;
            for (Main main : finalTestSpace.nodes) {
                try {
                    if(main.node.waitItem(c.getId(),4000).state != ItemState.APPROVED) {
                        count++;
                    }
                } catch (TimeoutException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println("Contract " + c.getId() + " is unknown to " + count + "node(s)");
        });

        Contract c = contractsToJoin.remove(Do.randomInt(contractsToJoin.size()));
        Contract main = c.createRevision(issuerKey);
        main.getStateData().set("amount","400");
        main.addRevokingItems(contractsToJoin.get(0),contractsToJoin.get(1),contractsToJoin.get(2));
        main.addSignerKey(issuerKey);
        main.seal();
        contractsToJoin.add(c);
        testSpace.client.register(main.getPackedTransaction(),1500);
        ItemResult ir;
        do {
            ir = testSpace.client.getState(main.getId());
            System.out.println(ir);
            Thread.sleep(1000);
        } while (ir.state.isPending());


        contractsToJoin.forEach(c1 -> {
            int count = 0;
            for (Main main1 : finalTestSpace.nodes) {
                try {
                    if(main1.node.waitItem(c1.getId(),4000).state != ItemState.APPROVED) {
                        count++;
                    }
                } catch (TimeoutException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println("Contract " + c.getId() + " is unknown to " + count + "node(s)");
        });

        assertEquals(ir.state,ItemState.APPROVED);

        testSpace.nodes.forEach(x -> x.shutdown());

    }

    @Test
    public void asdasd123() throws Exception {
        Map<HashId,Map<ItemState,Set<Integer>>> results = new HashMap<>();
        Map<HashId,Map<ItemState,Set<Integer>>> resultsRevoking = new HashMap<>();
        Map<HashId,Map<ItemState,Set<Integer>>> resultsNew  = new HashMap<>();
        TransactionPack tp = TransactionPack.unpack(Do.read("/Users/romanu/Downloads/ru/token106.unicon"));
        tp.getContract().check();
        System.out.println("Processing cost " + tp.getContract().getProcessedCostU());


        results.put(tp.getContract().getId(),new HashMap<>());
        tp.getContract().getRevokingItems().forEach(a -> {
            resultsRevoking.put(a.getId(),new HashMap<>());
        });

        tp.getContract().getNewItems().forEach(a -> {
            resultsNew.put(a.getId(),new HashMap<>());
        });

        PrivateKey key = new PrivateKey(Do.read("/Users/romanu/Downloads/ru/roman.uskov.privateKey.unikey"));
        Client clients = new Client("http://node-" + 1 + "-com.universa.io:8080", key, null, false);
        System.out.println(clients.getVersion());

       /* for (int i = 0; i < 33;i++) {
            Client c = clients.getClient(i);
            System.out.println("VL:" + c.setVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING, DatagramAdapter.VerboseLevel.DETAILED, DatagramAdapter.VerboseLevel.NOTHING));

        }*/

       //System.out.println(clients.getClient(30).resyncItem(HashId.withDigest("NPo4dIkNdgYfGiNrdExoX003+lFT/d45OA6GifmcRoTzxSRSm5c5jDHBSTaAS+QleuN7ttX1rTvSQbHIIqkcK/zWjx/fCpP9ziwsgXbyyCtUhLqP9G4YZ+zEY/yL/GVE")));




        for(int i = 0; i < 33;i++) {
            try {

                Client c = clients.getClient(i);
                //System.out.println("VL:" + c.setVerboseLevel(DatagramAdapter.VerboseLevel.NOTHING, DatagramAdapter.VerboseLevel.DETAILED, DatagramAdapter.VerboseLevel.NOTHING));
                int finalI = i;

                results.keySet().forEach(id -> {

                    try {
                        ItemResult ir = c.getState(id);
                        System.out.println("this " + id + " node: " + finalI +" " + ir.state + " - " + ir.createdAt + " " + ir.expiresAt);
                        if(!results.get(id).containsKey(ir.state)) {
                            results.get(id).put(ir.state,new HashSet<>());
                        }
                        results.get(id).get(ir.state).add(finalI+1);

                    } catch (ClientError clientError) {
                        clientError.printStackTrace();
                    }
                });

                resultsRevoking.keySet().forEach(id -> {

                    try {
                        ItemResult ir = c.getState(id);
                        System.out.println("revoking " + id + " node: " + finalI +" " + ir.state + " - " + ir.createdAt + " " + ir.expiresAt);
                        if(!resultsRevoking.get(id).containsKey(ir.state)) {
                            resultsRevoking.get(id).put(ir.state,new HashSet<>());
                        }
                        resultsRevoking.get(id).get(ir.state).add(finalI+1);
                    } catch (ClientError clientError) {
                        clientError.printStackTrace();
                    }
                });

                resultsNew.keySet().forEach(id -> {

                    try {
                        ItemResult ir = c.getState(id);
                        System.out.println("new " + id + " node: " + finalI +" " + ir.state + " - " + ir.createdAt + " " + ir.expiresAt);
                        if(!resultsNew.get(id).containsKey(ir.state)) {
                            resultsNew.get(id).put(ir.state,new HashSet<>());
                        }
                        resultsNew.get(id).get(ir.state).add(finalI+1);
                    } catch (ClientError clientError) {
                        clientError.printStackTrace();
                    }
                });
            } catch (IOException e) {
                System.out.println("failed to connect to " + i);
                e.printStackTrace();
            }

        }
        System.out.println("----THIS---");
        results.keySet().forEach(id -> {
            System.out.println(id);
            results.get(id).keySet().forEach(state -> {
                System.out.println(state + ": " + results.get(id).get(state).size() + " " + results.get(id).get(state));
            });
        });

        System.out.println("----REVOKING---");
        resultsRevoking.keySet().forEach(id -> {
            System.out.println(id);
            resultsRevoking.get(id).keySet().forEach(state -> {
                System.out.println(state + ": " + resultsRevoking.get(id).get(state).size() + " " + resultsRevoking.get(id).get(state));
            });
        });

        System.out.println("----NEW---");
        resultsNew.keySet().forEach(id -> {
            System.out.println(id);
            resultsNew.get(id).keySet().forEach(state -> {
                System.out.println(state + ": " + resultsNew.get(id).get(state).size() + " "+ resultsNew.get(id).get(state));
            });
        });

    }

    @Test
    public void asd() throws Exception {
        PrivateKey key = new PrivateKey(Do.read("/Users/romanu/Downloads/ru/roman.uskov.privateKey.unikey"));
        Set<PrivateKey> issuers = new HashSet<>();
        issuers.add(key);
        Set<PublicKey> owners = new HashSet<>();
        owners.add(key.getPublicKey());
        TestSpace testSpace = prepareTestSpace();
        testSpace.nodes.forEach(n->n.config.setIsFreeRegistrationsAllowedFromYaml(true));
        for(int i = 109; i < 110; i++) {
            Contract c = ContractsService.createTokenContract(issuers, owners, "100000.9", 0.01);
            c.setIssuerKeys(key.getPublicKey().getShortAddress());
            c.setCreatorKeys(key.getPublicKey().getShortAddress());
            c.setExpiresAt(ZonedDateTime.now().plusDays(10));
            c.seal();
            new FileOutputStream("/Users/romanu/Downloads/ru/token"+i+".unicon").write(c.getPackedTransaction());

            assertEquals(testSpace.client.register(Contract.fromPackedTransaction(Do.read("/Users/romanu/Downloads/ru/token"+i+".unicon")).getPackedTransaction(),10000).state,ItemState.APPROVED);


        }
    }

    private static final String REFERENCE_CONDITION_PREFIX = "ref.id==";
    private static final String REFERENCE_CONDITION2 = "ref.state.revision==1";

    @Test
    public void tttt() throws Exception {
        boolean refAsNew = true;

        PrivateKey key = TestKeys.privateKey(1);
        TestSpace testSpace = prepareTestSpace(key);

        testSpace.nodes.forEach( m -> {
            m.config.setIsFreeRegistrationsAllowedFromYaml(true);
        });

        Contract contractMark = new Contract(key);
        contractMark.seal();
        HashId origin = contractMark.getId();

        Contract contractMark2 = new Contract(key);
        contractMark2.seal();
        HashId origin2 = contractMark2.getId();


        Contract contract = new Contract(key);

        SimpleRole issuer = new SimpleRole("issuer");
        issuer.addKeyRecord(new KeyRecord(key.getPublicKey()));

        Reference ref = new Reference(contract);
        ref.type = Reference.TYPE_EXISTING_STATE;
        ref.setName(origin.toString());

        List<Object> conditionsList = new ArrayList<>();
        conditionsList.add(REFERENCE_CONDITION_PREFIX+origin.toBase64String());
        conditionsList.add(REFERENCE_CONDITION2);
        Binder conditions = Binder.of(Reference.conditionsModeType.all_of.name(),conditionsList);
        ref.setConditions(conditions);


        Reference ref2 = new Reference(contract);
        ref2.type = Reference.TYPE_EXISTING_STATE;
        ref2.setName(origin2.toString());

        List<Object> conditionsList2 = new ArrayList<>();
        conditionsList2.add(REFERENCE_CONDITION_PREFIX+origin2.toBase64String());
        conditionsList2.add(REFERENCE_CONDITION2);
        Binder conditions2 = Binder.of(Reference.conditionsModeType.all_of.name(),conditionsList2);
        ref2.setConditions(conditions2);


        //???
        contract.addReference(ref);
        issuer.addRequiredReference(ref, Role.RequiredMode.ALL_OF);
        contract.addReference(ref2);
        issuer.addRequiredReference(ref2, Role.RequiredMode.ALL_OF);

        contract.registerRole(issuer);
        contract.setOwnerKeys(key);
        contract.seal();
        ItemResult ir = testSpace.client.register(contract.getPackedTransaction(), 5000);

        //NO matching item for issuer reference in transaction pack
        assertEquals(ir.state,ItemState.DECLINED);

        contract.seal();
        contract.getTransactionPack().addReferencedItem(contractMark);
        contract.getTransactionPack().addReferencedItem(contractMark2);
        ir = testSpace.client.register(contract.getPackedTransaction(), 5000);

        //matching item for issuer reference is not APPROVED
        assertEquals(ir.state,ItemState.DECLINED);


        if(refAsNew) {
            contract.addNewItems(contractMark);
            contract.addNewItems(contractMark2);
        } else {
            testSpace.client.register(contractMark.getPackedTransaction(), 5000);
            testSpace.client.register(contractMark2.getPackedTransaction(), 5000);
        }

        contract.seal();
        if (!refAsNew) {
            contract.getTransactionPack().addReferencedItem(contractMark);
            contract.getTransactionPack().addReferencedItem(contractMark2);
        }
        ir = testSpace.client.register(contract.getPackedTransaction(), 5000);

        //all ok
        assertEquals(ir.state,ItemState.APPROVED);


        //no markContract is required referenced items of transaction pack
        contract = contract.createRevision(key);
        contract.addSignerKey(key);
        contract.setOwnerKeys(TestKeys.privateKey(2).getPublicKey());
        contract.seal();

        ir = testSpace.client.register(contract.getPackedTransaction(), 5000);
        //all ok

        assertEquals(ir.state,ItemState.APPROVED);

    }


    @Test
    public void testMarkReferences5() throws Exception {

        PrivateKey key = TestKeys.privateKey(1);
        TestSpace testSpace = prepareTestSpace(key);

        testSpace.nodes.forEach( m -> {
            m.config.setIsFreeRegistrationsAllowedFromYaml(true);
        });

        Contract contractMark = new Contract(key);
        contractMark.seal();
        HashId origin = contractMark.getId();

        Contract contractMark2 = new Contract(key);
        contractMark2.seal();
        HashId origin2 = contractMark2.getId();

        Contract contractMark3 = new Contract(key);
        contractMark3.seal();
        HashId origin3 = contractMark3.getId();

        Contract contractMark4 = new Contract(key);
        contractMark4.seal();
        HashId origin4 = contractMark4.getId();

        Contract contractMark5 = new Contract(key);
        contractMark5.seal();
        HashId origin5 = contractMark5.getId();


        Contract contract = new Contract(key);

        SimpleRole issuer = new SimpleRole("issuer");
        issuer.addKeyRecord(new KeyRecord(key.getPublicKey()));

        Reference ref = new Reference(contract);
        ref.type = Reference.TYPE_EXISTING_STATE;
        ref.setName(origin.toString());

        List<Object> conditionsList = new ArrayList<>();
        conditionsList.add(REFERENCE_CONDITION_PREFIX+origin.toBase64String());
        conditionsList.add(REFERENCE_CONDITION2);
        Binder conditions = Binder.of(Reference.conditionsModeType.all_of.name(),conditionsList);
        ref.setConditions(conditions);

        contract.addReference(ref);
        issuer.addRequiredReference(ref, Role.RequiredMode.ALL_OF);


        Reference ref2 = new Reference(contract);
        ref2.type = Reference.TYPE_EXISTING_STATE;
        ref2.setName(origin2.toString());

        List<Object> conditionsList2 = new ArrayList<>();
        conditionsList2.add(REFERENCE_CONDITION_PREFIX+origin2.toBase64String());
        conditionsList2.add(REFERENCE_CONDITION2);
        Binder conditions2 = Binder.of(Reference.conditionsModeType.all_of.name(),conditionsList2);
        ref2.setConditions(conditions2);
        
        contract.addReference(ref2);
        issuer.addRequiredReference(ref2, Role.RequiredMode.ALL_OF);


        Reference ref3 = new Reference(contract);
        ref3.type = Reference.TYPE_EXISTING_STATE;
        ref3.setName(origin3.toString());

        List<Object> conditionsList3 = new ArrayList<>();
        conditionsList3.add(REFERENCE_CONDITION_PREFIX+origin3.toBase64String());
        conditionsList3.add(REFERENCE_CONDITION2);
        Binder conditions3 = Binder.of(Reference.conditionsModeType.all_of.name(),conditionsList3);
        ref3.setConditions(conditions3);

        contract.addReference(ref3);
        issuer.addRequiredReference(ref3, Role.RequiredMode.ALL_OF);

        Reference ref4 = new Reference(contract);
        ref4.type = Reference.TYPE_EXISTING_STATE;
        ref4.setName(origin4.toString());

        List<Object> conditionsList4 = new ArrayList<>();
        conditionsList4.add(REFERENCE_CONDITION_PREFIX+origin4.toBase64String());
        conditionsList4.add(REFERENCE_CONDITION2);
        Binder conditions4 = Binder.of(Reference.conditionsModeType.all_of.name(),conditionsList4);
        ref4.setConditions(conditions4);

        contract.addReference(ref4);
        issuer.addRequiredReference(ref4, Role.RequiredMode.ALL_OF);


        Reference ref5 = new Reference(contract);
        ref5.type = Reference.TYPE_EXISTING_STATE;
        ref5.setName(origin5.toString());

        List<Object> conditionsList5 = new ArrayList<>();
        conditionsList5.add(REFERENCE_CONDITION_PREFIX+origin5.toBase64String());
        conditionsList5.add(REFERENCE_CONDITION2);
        Binder conditions5 = Binder.of(Reference.conditionsModeType.all_of.name(),conditionsList5);
        ref5.setConditions(conditions5);

        contract.addReference(ref5);
        issuer.addRequiredReference(ref5, Role.RequiredMode.ALL_OF);
        
        
        

        contract.registerRole(issuer);
        contract.setOwnerKeys(key);
        contract.seal();
        ItemResult ir = testSpace.client.register(contract.getPackedTransaction(), 5000);

        //NO matching item for issuer reference in transaction pack
        assertEquals(ir.state,ItemState.DECLINED);

        contract.seal();
        contract.getTransactionPack().addReferencedItem(contractMark);
        contract.getTransactionPack().addReferencedItem(contractMark2);
        ir = testSpace.client.register(contract.getPackedTransaction(), 5000);

        //matching item for issuer reference is not APPROVED
        assertEquals(ir.state,ItemState.DECLINED);


            contract.addNewItems(contractMark);
            contract.addNewItems(contractMark2);
            contract.addNewItems(contractMark3);

            testSpace.client.register(contractMark4.getPackedTransaction(), 5000);
            testSpace.client.register(contractMark5.getPackedTransaction(), 5000);

        contract.seal();

        contract.getTransactionPack().addReferencedItem(contractMark4);
        contract.getTransactionPack().addReferencedItem(contractMark5);

        ir = testSpace.client.register(contract.getPackedTransaction(), 5000);

        //all ok
        assertEquals(ir.state,ItemState.APPROVED);


        //no markContract is required referenced items of transaction pack
        contract = contract.createRevision(key);
        contract.addSignerKey(key);
        contract.setOwnerKeys(TestKeys.privateKey(2).getPublicKey());
        contract.seal();

        ir = testSpace.client.register(contract.getPackedTransaction(), 5000);
        //all ok

        assertEquals(ir.state,ItemState.APPROVED);

        testSpace.nodes.forEach(n -> n.shutdown());
    }


    @Test
    public void conversionSchemeUtoUTN() throws Exception {
        /////////////////////////////////////////////////
        //PREPARATION
        /////////////////////////////////////////////////
        PrivateKey universaAdminKey = TestKeys.privateKey(10);
        PrivateKey utnIssuerKey = TestKeys.privateKey(11);
        PrivateKey uIssuerKey = new PrivateKey(Do.read(Config.uKeyPath));
        PrivateKey userKey = TestKeys.privateKey(12);
        Set<PrivateKey> userKeys = new HashSet<>();
        userKeys.add(userKey);

        SimpleRole universaAdmin = new SimpleRole("universa_admin");
        universaAdmin.addKeyRecord(new KeyRecord(universaAdminKey.getPublicKey()));

        TestSpace testSpace = prepareTestSpace(userKey);
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));
        Set<PrivateKey> utnIssuer = new HashSet<>();
        utnIssuer.add(utnIssuerKey);

        Set<PublicKey> ownerKeys = new HashSet<>();
        ownerKeys.add(userKey.getPublicKey());
        Contract utnContract = ContractsService.createTokenContract(utnIssuer,ownerKeys,"100000");
        @NonNull ItemResult ir = testSpace.client.register(utnContract.getPackedTransaction());

        while(ir.state.isPending()) {
            Thread.sleep(500);
            ir = testSpace.client.getState(utnContract.getId());
        }

        assertEquals(ir.state, ItemState.APPROVED);
        /////////////////////////////////////////////////
        //PREPARATION END
        /////////////////////////////////////////////////


        //CREATE COMPOUND
        Contract compound = ContractsService.createSplit(utnContract, "150", "amount", new HashSet<>(),true);
        Contract paymentInUTNs = (Contract) compound.getNewItems().iterator().next();
        paymentInUTNs.setOwnerKeys(universaAdminKey);

        //CALCULATE AMOUNT OF U
        BigDecimal utnsPayed = new BigDecimal(paymentInUTNs.getStateData().getString("amount"));
        int unitsToIssue = utnsPayed.intValue()*100;

        //CREATE U CONTRACT
        //Create standart U contract
        Contract uContract = InnerContractsService.createFreshU(unitsToIssue, ownerKeys);
        Contract consent = ContractsService.addConsent(uContract, universaAdminKey.getPublicKey().getLongAddress());
        uContract.seal();
        compound.addNewItems(uContract);

        consent.addSignerKey(universaAdminKey);
        consent.seal();


        //TRY TO REGISTER COMPOUND WITH INVALID ISSUING REFERENCE INSIDE U
        compound.seal();
        compound.addSignatureToSeal(userKeys);


        //attempt to register
        ir = testSpace.client.register(compound.getPackedTransaction());
        while(ir.state.isPending()) {
            Thread.sleep(500);
            ir = testSpace.client.getState(compound.getId());
        }
        //blocked by uIssueBlocker reference
        assertEquals(ir.state,ItemState.DECLINED);


        compound.seal();
        compound.addSignatureToSeal(userKeys);
        //CREATE BATCH CONTAINING compound and its consent
        Contract batch = ContractsService.createBatch(userKeys, compound, consent);


        //REGISTER BATCH WITH VALID REFERENCE
        //reseal compound
        //attempt to register (uIssueBlocker reference is now valid)
        ir = testSpace.client.register(batch.getPackedTransaction());
        while(ir.state.isPending()) {
            Thread.sleep(500);
            ir = testSpace.client.getState(batch.getId());
        }
        //so everything is fine
        assertEquals(ir.state,ItemState.APPROVED);



        //REGISTER SAMPLE CONTRACT WITH PAYMENT
        //Try to register new contract and use uContract as payment
        Contract sampleContract = new Contract(userKey);
        sampleContract.seal();
        Parcel parcel = ContractsService.createParcel(sampleContract,uContract,1,userKeys);
        testSpace.client.registerParcel(parcel.pack(),5000);
        do {
            Thread.sleep(500);
            ir = testSpace.client.getState(sampleContract.getId());
        } while (ir.state.isPending());
        //so everything is fine
        ItemResult pr = testSpace.client.getState(parcel.getPaymentContract().getId());
        assertEquals(pr.state,ItemState.APPROVED);
        assertEquals(ir.state,ItemState.APPROVED);
        uContract = parcel.getPaymentContract();


        //REVOKE consent
        Contract revocation = ContractsService.createRevocation(consent, universaAdminKey);
        ir = testSpace.client.register(revocation.getPackedTransaction());
        while(ir.state.isPending()) {
            Thread.sleep(500);
            ir = testSpace.client.getState(revocation.getId());
        }
        //uIssueBlocker is revoked
        assertEquals(ir.state,ItemState.APPROVED);



        //REGISTER ANOTHER SAMPLE CONTRACT WITH PAYMENT
        //Try to register new contract and use uContract as payment
        sampleContract = new Contract(userKey);
        sampleContract.seal();
        parcel = ContractsService.createParcel(sampleContract,uContract,1,userKeys);
        testSpace.client.registerParcel(parcel.pack(),5000);
        do {
            Thread.sleep(500);
            ir = testSpace.client.getState(sampleContract.getId());
        } while (ir.state.isPending());
        //so everything should be fine so revokation of uIssueBlocker should not affect later usage of U
        assertEquals(ir.state,ItemState.APPROVED);

    }


    @Test
    public void refTest() throws Exception {
        PrivateKey key = TestKeys.privateKey(0);

        Contract contract1 = new Contract(key);
        Contract contract2 = new Contract(key);
        Contract contract3 = new Contract(key);
        Contract contract4 = new Contract(key);
        contract4.seal();

        contract1.addNewItems(contract2);
        contract3.addNewItems(contract4);

        Reference reference = new Reference();
        reference.name = "consent_"+contract4.getId();
        reference.type = Reference.TYPE_EXISTING_STATE;

        List<Object> conditionsList = new ArrayList<>();
        conditionsList.add(REFERENCE_CONDITION_PREFIX+contract4.getId().toBase64String());
        conditionsList.add(REFERENCE_CONDITION2);
        Binder conditions = Binder.of(Reference.conditionsModeType.all_of.name(),conditionsList);
        reference.setConditions(conditions);


        contract2.addReference(reference);
        contract2.getIssuer().addRequiredReference(reference, Role.RequiredMode.ALL_OF);

        contract3.seal();
        contract2.seal();
        contract1.seal();

        Contract c = ContractsService.createBatch(Do.listOf(key), contract1, contract3);


        TestSpace testSpace = prepareTestSpace();
        testSpace.nodes.forEach(n->n.config.setIsFreeRegistrationsAllowedFromYaml(true));

        ItemResult ir = testSpace.client.register(c.getPackedTransaction(), 10000);
        while (ir.state.isPending()) {
            ir = testSpace.client.getState(c.getId());
        }

        assertEquals(ir.state,ItemState.APPROVED);

    }



    @Test
    public void randomReferences() throws Exception {
        Random random = new Random();
        TestSpace testSpace = prepareTestSpace();
        testSpace.nodes.forEach(n->n.config.setIsFreeRegistrationsAllowedFromYaml(true));

        final int CONTRACTS_IN_BATCH = 20;
        final int REFS_COUNT = 1000;

        List<PrivateKey> keys100 = new ArrayList<>();
        for (int i = 0; i < CONTRACTS_IN_BATCH; ++i)
            keys100.add(new PrivateKey(2048));

        List<Contract> contracts100 = new ArrayList<>();
        for (int i = 0; i < CONTRACTS_IN_BATCH; ++i) {
            Contract c = new Contract(keys100.get(i));
            c.getStateData().put("some_value", 9000+random.nextInt(1000));
            contracts100.add(c);
        }

        Map<Integer, Integer> refsCasesCounter = new HashMap<>();
        for (int i = 1; i < REFS_COUNT; ++i) {
            int refForContract = random.nextInt(keys100.size());
            int refCase = random.nextInt(4);
            refsCasesCounter.put(refCase, refsCasesCounter.getOrDefault(refCase, 0) + 1);
            Reference ref = new Reference();
            switch (refCase) {
                case 0:
                    ref.type = Reference.TYPE_EXISTING_STATE;
                    ref.setConditions(Binder.of(Reference.conditionsModeType.all_of.name(),asList("ref.issuer=="+keys100.get(random.nextInt(keys100.size())).getPublicKey().getShortAddress())));
                    break;
                case 1:
                    ref.type = Reference.TYPE_EXISTING_DEFINITION;
                    ref.setConditions(Binder.of(Reference.conditionsModeType.all_of.name(),asList("ref.owner=="+keys100.get(random.nextInt(keys100.size())).getPublicKey().getLongAddress())));
                    break;
                case 2:
                    ref.type = Reference.TYPE_EXISTING_STATE;
                    ref.setConditions(Binder.of(
                            Reference.conditionsModeType.all_of.name(),
                            asList(
                                    "ref.state.data.some_value=="+contracts100.get(random.nextInt(contracts100.size())).getStateData().getStringOrThrow("some_value")
                            )));
                    break;
                case 3:
                    ref.type = Reference.TYPE_EXISTING_STATE;
                    ref.setConditions(Binder.of(
                            Reference.conditionsModeType.all_of.name(),
                            asList("ref.state.data.some_value<=1000")));
                    break;
            }
            contracts100.get(refForContract).addReference(ref);
        }
        System.out.println("\nrefs cases:");
        refsCasesCounter.forEach((k, v) -> System.out.println("  case " + k + ": " + v));

        Contract batch = new Contract(new PrivateKey(2048));
        for (int i = 0; i < keys100.size(); ++i)
            batch.addNewItems(contracts100.get(i));
        batch.seal();

        ItemResult ir = testSpace.client.register(batch.getPackedTransaction(), 30000);
        if (ir.errors.size() > 0) {
            System.out.println("\n\nerrors:");
            ir.errors.forEach(e -> System.out.println("  " + e));
            System.out.println();
        }
        //assertEquals(ItemState.APPROVED, ir.state);
        assertEquals(ItemState.DECLINED, ir.state); // must be declined due to ref.state.data.some_value<=1000
    }

    @Test
    public void jsDemo1() throws Exception {
        TestSpace testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(m -> m.config.setIsFreeRegistrationsAllowedFromYaml(true));

        Contract contract = new Contract(TestKeys.privateKey(1));
        ModifyDataPermission perm = new ModifyDataPermission(contract.getOwner(), new Binder());
        perm.addField("test_value", Arrays.asList("0", "1"));
        contract.addPermission(perm);
        contract.getStateData().set("test_value", "0");
        String js = "";
        js += "print('demo1');";
        js += "print('  create new revision...');";
        js += "rev = jsApi.getCurrentContract().createRevision();";
        js += "print('  new revision: ' + rev.getRevision());";
        js += "var oldValue = rev.getStateDataField('test_value');";
        js += "var newValue = oldValue=='0' ? '1' : '0';";
        js += "print('  change test_value: ' + oldValue + ' -> ' + newValue);";
        js += "rev.setStateDataField('test_value', newValue);";
        js += "result = rev";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        assertTrue(contract.check());

        ItemResult ir = testSpace.client.register(contract.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir.state);

        for (int i = 0; i < 10; ++i) {
            contract = ((JSApiContract) contract.execJS(js.getBytes())).extractContract(new JSApiAccessor());
            contract.addSignerKey(TestKeys.privateKey(1));
            contract.seal();
            assertEquals(i%2==0 ? "1" : "0", contract.getStateData().getStringOrThrow("test_value"));
            ir = testSpace.client.register(contract.getPackedTransaction(), 5000);
            assertEquals(ItemState.APPROVED, ir.state);
        }

        testSpace.nodes.forEach(m -> m.shutdown());
    }

    @Test
    public void jsDemo2() throws Exception {
        TestSpace testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(m -> m.config.setIsFreeRegistrationsAllowedFromYaml(true));

        Contract contract = new Contract(TestKeys.privateKey(1));
        Binder permParams = new Binder();
        permParams.set("min_value", 1);
        permParams.set("min_step", 1);
        permParams.set("max_step", 1);
        permParams.set("field_name", "test_value");
        ChangeNumberPermission perm = new ChangeNumberPermission(contract.getOwner(), permParams);
        contract.addPermission(perm);
        contract.getStateData().set("test_value", 11);
        String js = "";
        js += "print('demo2');";
        js += "print('  create new revision...');";
        js += "rev = jsApi.getCurrentContract().createRevision();";
        js += "print('  new revision: ' + rev.getRevision());";
        js += "var oldValue = parseInt(rev.getStateDataField('test_value'));";
        js += "var newValue = (oldValue + 1) >> 0;"; // '>> 0' converts js-number to int
        js += "print('  change test_value: ' + oldValue + ' -> ' + newValue);";
        js += "rev.setStateDataField('test_value', newValue);";
        js += "result = rev";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        assertTrue(contract.check());

        ItemResult ir = testSpace.client.register(contract.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir.state);

        for (int i = 0; i < 10; ++i) {
            contract = ((JSApiContract) contract.execJS(js.getBytes())).extractContract(new JSApiAccessor());
            contract.addSignerKey(TestKeys.privateKey(1));
            contract.seal();
            assertEquals(i+12, contract.getStateData().getIntOrThrow("test_value"));
            ir = testSpace.client.register(contract.getPackedTransaction(), 5000);
            assertEquals(ItemState.APPROVED, ir.state);
        }

        testSpace.nodes.forEach(m -> m.shutdown());
    }

    @Test
    public void jsAddPermission() throws Exception {
        TestSpace testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(m -> m.config.setIsFreeRegistrationsAllowedFromYaml(true));

        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        Contract contract = new Contract(TestKeys.privateKey(0));
        contract.getStateData().set("testval", 3);
        String js = "";
        js += "print('addPermission');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var changeNumberPermission = jsApi.getPermissionBuilder().createChangeNumberPermission(simpleRole, " +
                "{field_name: 'testval', min_value: 3, max_value: 80, min_step: 1, max_step: 3}" +
                ");";
        js += "jsApi.getCurrentContract().addPermission(changeNumberPermission);";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        contract.getState().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.execJS(js.getBytes());
        contract.seal();

        ItemResult ir = testSpace.client.register(contract.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir.state);

        Contract newRev = contract.createRevision();
        newRev.addSignerKey(TestKeys.privateKey(0));
        newRev.getStateData().set("testval", 5);
        newRev.seal();

        ir = testSpace.client.register(newRev.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir.state);

        testSpace.nodes.forEach(m -> m.shutdown());
    }

    @Ignore
    @Test
    public void registerFromFile() throws Exception {
        TestSpace testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(m -> m.config.setIsFreeRegistrationsAllowedFromYaml(true));

        Path path = Paths.get("/tmp/not3.unicon");
        byte[] testTransactionPackBytes = Files.readAllBytes(path);
        Contract contract = Contract.fromPackedTransaction(testTransactionPackBytes);

        System.out.println("======================");
        System.out.println("check(): " + contract.check());
        System.out.println("------- errors -------");
        contract.traceErrors();
        int i = 0;
        for (Approvable a : contract.getNewItems()) {
            Contract nc = (Contract) a;
            System.out.println("------- errors n"+i+" ----");
            System.out.println("  check: " + nc.check());
            nc.traceErrors();
            ++i;
        }
        System.out.println("======================");

        System.out.println("hashId: " + contract.getId().toBase64String());
        testSpace.node.setVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
        ItemResult itemResult = testSpace.client.register(testTransactionPackBytes, 5000);
        ItemResult itemResult2 = testSpace.client.getState(contract.getId());

        System.out.println("itemResult: " + itemResult);
        System.out.println("itemResult2: " + itemResult2);

        testSpace.nodes.forEach(m -> m.shutdown());
    }

    @Test
    public void getStateWithNoLedgerCache() throws Exception {
        TestSpace testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(m -> m.config.setIsFreeRegistrationsAllowedFromYaml(true));
        testSpace.nodes.forEach(m -> ((PostgresLedger)m.node.getLedger()).enableCache(false));

        //SHUTDOWN LAST NODE
        testSpace.nodes.remove(testSpace.nodes.size()-1).shutdown();
        Thread.sleep(4000);

        Contract rev1 = new Contract(TestKeys.privateKey(0));
        rev1.getStateData().set("field1", 33);
        Permission permission = new ChangeNumberPermission(rev1.getOwner(), Binder.of("field_name", "field1"));
        rev1.addPermission(permission);
        rev1.seal();
        ItemResult ir1 = testSpace.client.register(rev1.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir1.state);

        Contract rev2 = rev1.createRevision();
        rev2.getStateData().set("field1", 34);
        rev2.addSignerKey(TestKeys.privateKey(0));
        rev2.seal();
        ItemResult ir2 = testSpace.client.register(rev2.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir2.state);
        ir1 = testSpace.client.register(rev1.getPackedTransaction(), 5000);
        assertEquals(ItemState.REVOKED, ir1.state);

        testSpace.nodes.forEach(m -> m.shutdown());
    }

    @Test
    public void resyncWithNoLedgerCache() throws Exception {
        TestSpace testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));
        testSpace.nodes.forEach(m -> ((PostgresLedger)m.node.getLedger()).enableCache(false));
        testSpace.nodes.get(testSpace.nodes.size()-1).shutdown();

        NSmartContract rev1 = new NSmartContract(TestKeys.privateKey(0));
        rev1.getStateData().set("field1", 33);
        Permission permission = new ChangeNumberPermission(rev1.getOwner(), Binder.of("field_name", "field1"));
        rev1.addPermission(permission);
        rev1.seal();
        ItemResult ir1 = testSpace.client.register(rev1.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir1.state);

        Contract rev2 = rev1.createRevision();
        rev2.getStateData().set("field1", 34);
        rev2.addSignerKey(TestKeys.privateKey(0));
        rev2.seal();
        ItemResult ir2 = testSpace.client.register(rev2.getPackedTransaction(), 5000);
        assertEquals(ItemState.APPROVED, ir2.state);
        ir1 = testSpace.client.register(rev1.getPackedTransaction(), 5000);
        assertEquals(ItemState.REVOKED, ir1.state);

        //recreate nodes
        for (int i = 0; i < testSpace.nodes.size()-1; ++i)
            testSpace.nodes.get(i).shutdown();
        Thread.sleep(2000);
        testSpace = prepareTestSpace(TestKeys.privateKey(0));
        testSpace.nodes.forEach(n -> n.config.setIsFreeRegistrationsAllowedFromYaml(true));

        //put some envorinment for rev1
        Ledger ledger = testSpace.nodes.get(testSpace.nodes.size()-1).node.getLedger();
        assertNull(ledger.getEnvironment(rev1.getId()));
        NImmutableEnvironment environment = new NImmutableEnvironment(rev1,new Binder(),Do.listOf(),Do.listOf(),null);
        ledger.saveEnvironment(environment);
        assertNotNull(ledger.getEnvironment(rev1.getId()));

        System.out.println("\n========== resyncing ==========\n");
        testSpace.nodes.get(testSpace.clients.size()-1).setVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
        testSpace.clients.get(testSpace.clients.size()-1).resyncItem(rev1.getId());
        long millisToWait = 60000;
        long waitPeriod = 2000;
        ItemResult ir = null;
        while (millisToWait > 0) {
            Thread.sleep(waitPeriod);
            millisToWait -= waitPeriod;
            ir = testSpace.clients.get(testSpace.clients.size()-1).getState(rev1.getId());
            if (ir.state == ItemState.REVOKED)
                break;
        }
        assertEquals(ItemState.REVOKED, ir.state);
        assertNull(ledger.getEnvironment(rev1.getId()));

        testSpace.nodes.forEach(n->n.shutdown());
    }

    @Test
    public void checkWhiteListKey() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);
        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        Contract testContract = new Contract(myKey);
        for (int i = 0; i < 10; i++) {
            Contract nc = new Contract(myKey);
            nc.seal();
            testContract.addNewItems(nc);
        }
        testContract.seal();
        assertTrue(testContract.isOk());

        Parcel parcel = createParcelWithFreshU(client, testContract,Do.listOf(myKey));
        client.registerParcel(parcel.pack(), 15000);

        ItemResult itemResult = client.getState(parcel.getPayloadContract().getId());
        System.out.println(">> state: " + itemResult);

        assertEquals (ItemState.APPROVED, itemResult.state);

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkNotWhiteListKey() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        //remove all keys and addresses from white list
        mm.forEach(x -> {
            x.config.getKeysWhiteList().clear();
            x.config.getAddressesWhiteList().clear();
        });

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);
        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        Contract testContract = new Contract(myKey);
        for (int i = 0; i < 10; i++) {
            Contract nc = new Contract(myKey);
            nc.seal();
            testContract.addNewItems(nc);
        }
        testContract.seal();
        assertTrue(testContract.isOk());

        Set<PublicKey> ownerKeys = new HashSet();
        Collection<PrivateKey> keys = Do.listOf(myKey);
        keys.stream().forEach(key->ownerKeys.add(key.getPublicKey()));
        Contract stepaU = InnerContractsService.createFreshU(100000000, ownerKeys);
        stepaU.check();
        stepaU.traceErrors();

        PrivateKey clientPrivateKey = client.getSession().getPrivateKey();
        PrivateKey newPrivateKey = new PrivateKey(Do.read("./src/test_contracts/keys/u_key.private.unikey"));
        client.getSession().setPrivateKey(newPrivateKey);
        client.restart();

        ItemResult itemResult = client.register(stepaU.getPackedTransaction(), 5000);

        client.getSession().setPrivateKey(clientPrivateKey);
        client.restart();

        //check error
        assertEquals(itemResult.errors.size(), 1);
        assertEquals(itemResult.errors.get(0).getMessage(), "command needs client key from whitelist");

        assertEquals(ItemState.UNDEFINED, itemResult.state);
        Set<PrivateKey> keySet = new HashSet<>();
        keySet.addAll(keys);
        Parcel parcel = ContractsService.createParcel(testContract, stepaU, 150, keySet);
        client.registerParcel(parcel.pack(), 15000);

        itemResult = client.getState(parcel.getPayloadContract().getId());
        System.out.println(">> state: " + itemResult);

        assertEquals(ItemState.UNDEFINED, itemResult.state);

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkWhiteListAddress() throws Exception {
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mm.add(createMain("node" + (i + 1), false));
        }

        PrivateKey newPrivateKey = new PrivateKey(Do.read("./src/test_contracts/keys/u_key.private.unikey"));

        //remove all keys and addresses from white list
        mm.forEach(x -> {
            x.config.getKeysWhiteList().clear();
            x.config.getAddressesWhiteList().clear();
        });

        //add address to white list
        mm.forEach(x -> x.config.getAddressesWhiteList().add(new KeyAddress(newPrivateKey.getPublicKey(), 0, true)));

        Main main = mm.get(0);
        PrivateKey myKey = TestKeys.privateKey(3);
        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        Contract testContract = new Contract(myKey);
        for (int i = 0; i < 10; i++) {
            Contract nc = new Contract(myKey);
            nc.seal();
            testContract.addNewItems(nc);
        }
        testContract.seal();
        assertTrue(testContract.isOk());

        Set<PublicKey> ownerKeys = new HashSet();
        Collection<PrivateKey> keys = Do.listOf(myKey);
        keys.stream().forEach(key->ownerKeys.add(key.getPublicKey()));
        Contract stepaU = InnerContractsService.createFreshU(100000000, ownerKeys);
        stepaU.check();
        stepaU.traceErrors();

        PrivateKey clientPrivateKey = client.getSession().getPrivateKey();
        client.getSession().setPrivateKey(newPrivateKey);
        client.restart();

        ItemResult itemResult = client.register(stepaU.getPackedTransaction(), 5000);

        client.getSession().setPrivateKey(clientPrivateKey);
        client.restart();

        assertEquals(ItemState.APPROVED, itemResult.state);
        Set<PrivateKey> keySet = new HashSet<>();
        keySet.addAll(keys);
        Parcel parcel = ContractsService.createParcel(testContract, stepaU, 150, keySet);
        client.registerParcel(parcel.pack(), 15000);

        itemResult = client.getState(parcel.getPayloadContract().getId());
        System.out.println(">> state: " + itemResult);

        assertEquals(ItemState.APPROVED, itemResult.state);

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkLimitRequestsForKey() throws Exception {

        PrivateKey myKey = TestKeys.privateKey(3);
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));

        mm.forEach(x -> x.config.setIsFreeRegistrationsAllowedFromYaml(true));

        Main main = mm.get(0);
        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        for (int i = 0; i < main.config.getLimitRequestsForKeyPerMinute() * 2; i++) {
            Contract testContract = new Contract(myKey);
            testContract.seal();
            assertTrue(testContract.isOk());

            Set<PublicKey> ownerKeys = new HashSet();
            Collection<PrivateKey> keys = Do.listOf(myKey);
            keys.stream().forEach(key -> ownerKeys.add(key.getPublicKey()));
            Contract stepaU = InnerContractsService.createFreshU(100000000, ownerKeys);
            stepaU.check();
            stepaU.traceErrors();

            client.register(stepaU.getPackedTransaction());

            Set<PrivateKey> keySet = new HashSet<>();
            keySet.addAll(keys);
            Parcel parcel = ContractsService.createParcel(testContract, stepaU, 150, keySet);
            client.registerParcel(parcel.pack());
        }

        for (int i = 0; i < main.config.getLimitRequestsForKeyPerMinute(); i++)
            System.out.println(">> storage rate: " + client.storageGetRate());

        String exception = "";
        try {
            client.storageGetRate();            // limited request
        } catch (Exception e) {
            System.out.println("Client exception: " + e.toString());
            exception = e.getMessage();
        }

        assertEquals(exception, "ClientError: COMMAND_FAILED exceeded the limit of requests for key per minute, please call again after a while");

        System.out.println("Wait 1 munite...");
        Thread.currentThread().sleep(60000);

        for (int i = 0; i < main.config.getLimitRequestsForKeyPerMinute(); i++)
            System.out.println(">> uns rate: " + client.unsRate());

        exception = "";
        try {
            client.unsRate();                   // limited request
        } catch (Exception e) {
            System.out.println("Client exception: " + e.toString());
            exception = e.getMessage();
        }

        assertEquals(exception, "ClientError: COMMAND_FAILED exceeded the limit of requests for key per minute, please call again after a while");

        System.out.println("Wait 1 munite...");
        Thread.currentThread().sleep(60000);

        for (int i = 0; i < main.config.getLimitRequestsForKeyPerMinute(); i++)
            client.getStats(90);

        exception = "";
        try {
            client.getStats(90);                // limited request
        } catch (Exception e) {
            System.out.println("Client exception: " + e.toString());
            exception = e.getMessage();
        }

        assertEquals(exception, "ClientError: COMMAND_FAILED exceeded the limit of requests for key per minute, please call again after a while");

        mm.forEach(x -> x.shutdown());
    }

    @Test
    public void checkUnlimitRequestsForKey() throws Exception {

        PrivateKey myKey = TestKeys.privateKey(3);
        List<Main> mm = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            mm.add(createMain("node" + (i + 1), false));

        mm.forEach(x -> x.config.setIsFreeRegistrationsAllowedFromYaml(true));

        Main main = mm.get(0);
        Client client = null;
        try {
            client = new Client(myKey, main.myInfo, null);
        } catch (Exception e) {
            System.out.println("prepareClient exception: " + e.toString());
        }

        Set<PublicKey> ownerKeys = new HashSet();
        Set<PrivateKey> keys = new HashSet();
        keys.add(myKey);
        ownerKeys.add(myKey.getPublicKey());
        Contract payment = InnerContractsService.createFreshU(100000000, ownerKeys);
        payment.check();
        payment.traceErrors();

        client.register(payment.getPackedTransaction());

        Thread.currentThread().sleep(5000);

        mm.forEach(x -> x.config.setIsFreeRegistrationsAllowedFromYaml(false));

        // reaching requests limit
        for (int i = 0; i < main.config.getLimitRequestsForKeyPerMinute(); i++)
            System.out.println(">> storage rate: " + client.storageGetRate());

        String exception = "";
        try {
            client.storageGetRate();            // limited request
        } catch (Exception e) {
            System.out.println("Client exception: " + e.toString());
            exception = e.getMessage();
        }

        assertEquals(exception, "ClientError: COMMAND_FAILED exceeded the limit of requests for key per minute, please call again after a while");

        // set unlimited requests
        Contract unlimitContract = ContractsService.createContractForUnlimitKey(
                myKey.getPublicKey(), payment, main.config.getUnlimitPayment(), keys);

        unlimitContract.check();
        unlimitContract.traceErrors();
        assertTrue(unlimitContract.isOk());

        ItemResult itemResult = client.register(unlimitContract.getPackedTransaction());

        Thread.currentThread().sleep(5000);

        itemResult = client.getState(unlimitContract.getId());
        System.out.println(">> state: " + itemResult);

        assertEquals(ItemState.APPROVED, itemResult.state);

        // unlimited requests
        for (int i = 0; i < main.config.getLimitRequestsForKeyPerMinute() * 2; i++)
            System.out.println(">> storage rate: " + client.storageGetRate());

        for (int i = 0; i < main.config.getLimitRequestsForKeyPerMinute() * 2; i++)
            System.out.println(">> uns rate: " + client.unsRate());

        for (int i = 0; i < main.config.getLimitRequestsForKeyPerMinute() * 2; i++)
            client.getStats(90);

        mm.forEach(x -> x.shutdown());
    }
}