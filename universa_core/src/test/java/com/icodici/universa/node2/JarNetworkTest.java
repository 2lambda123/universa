package com.icodici.universa.node2;

import com.icodici.crypto.PrivateKey;
import com.icodici.crypto.PublicKey;
import com.icodici.universa.contract.*;
import com.icodici.universa.contract.services.ContractStorageSubscription;
import com.icodici.universa.contract.services.SlotContract;
import com.icodici.universa.node.*;
import com.icodici.universa.node.network.TestKeys;
import com.icodici.universa.node2.network.Client;
import net.sergeych.boss.Boss;
import net.sergeych.tools.Binder;
import net.sergeych.tools.Do;
import net.sergeych.utils.Base64;
import net.sergeych.utils.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class JarNetworkTest extends TestCase {

    private static final int NODES_COUNT = 8;
    private static Client whiteClient = null;
    private static Client normalClient = null;
    private static ArrayList<Client> normalClients = new ArrayList<>();
    private static Contract paymentContract = null;
    private static PrivateKey paymentContractPrivKey = null;
    private static Config config;
    private static Process tunnelProcess;
    private static PostgresLedger ledger;

    @BeforeClass
    public static void beforeClass() throws Exception {
        String nodeUrl = "http://node-1-pro.universa.io:8080";
        String dbUrl = "jdbc:postgresql://localhost:15432/universa_node?user=universa&password=fuSleaphs8";
        tunnelProcess = Runtime.getRuntime().exec("ssh -N -L 15432:127.0.0.1:5432 deploy@dd.node-1-pro.universa.io -p 54324");
        int attempts = 10;
        while(true) {
            Thread.sleep(500);
            try {
                ledger = new PostgresLedger(dbUrl);
                break;
            } catch (Exception e) {
                if(attempts-- <= 0) {
                    throw e;
                }
            }
        }

        PrivateKey clientKey = TestKeys.privateKey(0);
        whiteClient = new Client(nodeUrl, clientKey, null, false);
        normalClient = new Client(nodeUrl, new PrivateKey(2048), null, false);
        paymentContract = Contract.fromPackedTransaction(Base64.decodeLines(uno_flint004_rev1_bin_b64));
        paymentContractPrivKey = new PrivateKey(Base64.decodeLines(uno_flint004_privKey_b64));

        for(int i = 0; i < NODES_COUNT;i++) {
            normalClients.add(new Client("http://node-"+(i+1)+"-pro.universa.io:8080",new PrivateKey(2048),null));
        }
        config = new Config();
        config.setConsensusConfigUpdater((config, n) -> {
            // Until we fix the announcer
            int negative = (int) Math.ceil(n * 0.11);
            if (negative < 1)
                negative = 1;
            int positive = (int) Math.floor(n * 0.90);
            if( negative+positive == n)
                negative += 1;
            int resyncBreak = (int) Math.ceil(n * 0.2);
            if (resyncBreak < 1)
                resyncBreak = 1;
            if( resyncBreak+positive == n)
                resyncBreak += 1;

            config.setPositiveConsensus(positive);
            config.setNegativeConsensus(negative);
            config.setResyncBreakConsensus(resyncBreak);
        });
        config.updateConsensusConfig(NODES_COUNT);
    }

    @AfterClass
    public static void afterClass()  throws Exception {
        if(tunnelProcess != null)
            tunnelProcess.destroy();
    }


    @Test
    public void checkLedger() throws Exception {
        Map<ItemState, Integer> size = ledger.getLedgerSize(null);
        for (ItemState state : size.keySet()) {
            System.out.println(state + " : " + size.get(state));
        }
    }



    @Test
    public void registerSimpleContractWhite() throws Exception {
        Contract whiteContract = new Contract(TestKeys.privateKey(0));
        whiteContract.seal();

        System.out.println("whiteClient.register(whiteContract)...");
        ItemResult itemResult = whiteClient.register(whiteContract.getPackedTransaction(), 5000);
        System.out.println("whiteClient.register(whiteContract)... done! itemResult: " + itemResult.state);

        itemResult = whiteClient.getState(whiteContract.getId());
        System.out.println("whiteClient.getState(whiteContract): " + itemResult.state);
        assertEquals(ItemState.APPROVED, itemResult.state);
    }

    @Test
    public void registerSimpleContractNormal() throws Exception {
        Contract simpleContract = new Contract(TestKeys.privateKey(0));
        simpleContract.seal();

        registerAndCheckApproved(simpleContract);
    }



    @Test
    public void registerManySimpleContractsWhite() throws Exception {
        int CONTRACTS_PER_THREAD = 20;
        int THREADS_COUNT = 4;
        AtomicLong totalCounter = new AtomicLong(0);
        Runnable r = () -> {
            try {
                Client cln = createWhiteClient();
                int nodeNumber = cln.getNodeNumber();
                System.out.println("nodeNumber: " + nodeNumber);
                for (int i = 0; i < CONTRACTS_PER_THREAD; ++i) {
                    Contract whiteContract = new Contract(TestKeys.privateKey(nodeNumber-1));
                    whiteContract.seal();
                    ItemResult itemResult = cln.register(whiteContract.getPackedTransaction(), 15000);
                    assertEquals(ItemState.APPROVED, itemResult.state);
                    totalCounter.incrementAndGet();
                }
            } catch (Exception e) {
                System.out.println("error: " + e.toString());
            }
        };
        List<Thread> threadList = new ArrayList<>();
        for (int i = 0; i < THREADS_COUNT; ++i) {
            Thread t = new Thread(r);
            t.start();
            threadList.add(t);
        }
        Thread heartbeat = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(1000);
                    System.out.println("totalCounter: " + totalCounter.get());
                }
            } catch (Exception e) {
                System.out.println("totalCounter: " + totalCounter.get());
            }
        });
        heartbeat.start();
        for (Thread t : threadList)
            t.join();
        heartbeat.interrupt();
        heartbeat.join();
    }



    @Test
    public void checkPaymentContract() throws Exception {
        // to register manually, execute from deploy project:
        // bin/sql_all pro "insert into ledger(hash,state,created_at, expires_at, locked_by_id) values(decode('9186C0A9E9471E4559E74B5DAC3DBBB8445807DF80CAE4CE06FDB6588FAEBA1CE004AD378BEF3C445DECF3375E3CA5FD16227DBE5831A21207BB1BD21C85F30D0CED014E152F77E62082E0442FBD9FD2458C20778F7501B5D425AF9984062E54','hex'),'4','1520977039','1552513039','0');"
        // to erase all ledgers, execute:
        // bin/sql_all pro "truncate table ledger"
        // (after erasing ledgers, items still stay in cache -> need to restart (or redeploy) nodes)

        Contract contract = paymentContract;
        contract.check();
        System.out.println("uno bin: " + Base64.encodeString(contract.getPackedTransaction()));
        System.out.println("uno hashId: " + Bytes.toHex(contract.getId().getDigest()).replace(" ", ""));
        System.out.println("approved ord: " + ItemState.APPROVED.ordinal());
        System.out.println("getCreatedAt: " + StateRecord.unixTime(contract.getCreatedAt()));
        System.out.println("getExpiresAt: " + StateRecord.unixTime(contract.getExpiresAt()));

        ItemResult itemResult = normalClient.getState(contract.getId());
        System.out.println("getState... done! itemResult: " + itemResult.state);
    }



    @Test
    public void registerSimpleContractWithPayment() throws Exception {
        Contract contractToRegister = new Contract(TestKeys.privateKey(10));
        contractToRegister.seal();
        ItemResult itemResult = normalClient.register(contractToRegister.getPackedTransaction(), 5000);
        System.out.println("register... done! itemResult: " + itemResult.state);
        assertEquals(ItemState.UNDEFINED, itemResult.state);

        Parcel parcel = ContractsService.createParcel(contractToRegister, paymentContract, 1, Stream.of(paymentContractPrivKey).collect(Collectors.toSet()), true);
        normalClient.registerParcel(parcel.pack(), 5000);
        itemResult = normalClient.getState(parcel.getPaymentContract().getId());
        if (itemResult.state == ItemState.APPROVED)
            paymentContract = parcel.getPaymentContract();
        System.out.println("registerParcel... done!");
        System.out.println("parcel.paymentContract.itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);
        itemResult = normalClient.getState(contractToRegister.getId());
        System.out.println("contractToRegister.itemResult: " + itemResult);
        assertEquals(ItemState.APPROVED, itemResult.state);
    }



    @Test
    public void registerSeveralSimpleContractWithPayment() throws Exception {
        for (int i = 0; i < 20; ++i) {
            System.out.println("\ni = " + i);
            Contract contractToRegister = new Contract(TestKeys.privateKey(10));
            contractToRegister.seal();
            ItemResult itemResult = normalClient.register(contractToRegister.getPackedTransaction(), 5000);
            System.out.println("register... done! itemResult: " + itemResult.state);
            assertEquals(ItemState.UNDEFINED, itemResult.state);

            Parcel parcel = ContractsService.createParcel(contractToRegister, paymentContract, 1, Stream.of(paymentContractPrivKey).collect(Collectors.toSet()), true);
            normalClient.registerParcel(parcel.pack(), 5000);
            itemResult = normalClient.getState(parcel.getPaymentContract().getId());
            if (itemResult.state == ItemState.APPROVED)
                paymentContract = parcel.getPaymentContract();
            System.out.println("registerParcel... done!");
            System.out.println("parcel.paymentContract.itemResult: " + itemResult);
            assertEquals(ItemState.APPROVED, itemResult.state);
            itemResult = normalClient.getState(contractToRegister.getId());
            System.out.println("contractToRegister.itemResult: " + itemResult);
            assertEquals(ItemState.APPROVED, itemResult.state);
        }
    }



    private Client createWhiteClient() {
        try {
            int nodeNumber = ThreadLocalRandom.current().nextInt(1, 11);
            String nodeUrl = "http://node-" + nodeNumber + "-pro.universa.io:8080";
            PrivateKey clientKey = TestKeys.privateKey(nodeNumber-1);
            return new Client(nodeUrl, clientKey, null, false);
        } catch (Exception e) {
            System.out.println("createWhiteClient exception: " + e.toString());
            return null;
        }
    }

    protected static final String ROOT_PATH = "./src/test_contracts/";

    Contract tuContract;
    Object tuContractLock = new Object();

    protected Contract getApprovedTUContract() throws Exception {
        synchronized (tuContractLock) {
            if (tuContract == null) {
                PrivateKey ownerKey = new PrivateKey(Do.read(ROOT_PATH + "keys/stepan_mamontov.private.unikey"));
                Set<PublicKey> keys = new HashSet();
                keys.add(ownerKey.getPublicKey());
                Contract stepaTU = InnerContractsService.createFreshTU(100000000, keys);
                stepaTU.check();
                stepaTU.traceErrors();
                System.out.println("register new TU ");
                whiteClient.register(stepaTU.getPackedTransaction(),15000);
                tuContract = stepaTU;
            }
            int needRecreateTuContractNum = 0;
            for (Client client : normalClients) {
                int attempts = 10;
                ItemResult itemResult = client.getState(tuContract.getId());
                while(itemResult.state.isPending() && attempts-- > 0) {
                    itemResult = client.getState(tuContract.getId());
                    Thread.sleep(500);
                }
                if (itemResult.state != ItemState.APPROVED) {
                    System.out.println("TU: node " + client.getNodeNumber() + " result: " + itemResult);
                    needRecreateTuContractNum ++;
                }
            }

            int recreateBorder = NODES_COUNT - config.getPositiveConsensus() - 1;
            if(recreateBorder < 0)
                recreateBorder = 0;
            if (needRecreateTuContractNum > recreateBorder) {
                tuContract = null;
                Thread.sleep(1000);
                return getApprovedTUContract();
            }
            return tuContract;
        }
    }


    public synchronized Parcel createParcelWithClassTU(Contract c, Set<PrivateKey> keys) throws Exception {
        Contract tu = getApprovedTUContract();
        Parcel parcel =  ContractsService.createParcel(c, tu, 150, keys);
        return parcel;
    }

    protected synchronized Parcel registerWithNewParcel(Contract c) throws Exception {
        Set<PrivateKey> stepaPrivateKeys = new HashSet<>();
        stepaPrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "keys/stepan_mamontov.private.unikey")));
        Parcel parcel = createParcelWithClassTU(c, stepaPrivateKeys);
        System.out.println("register  parcel: " + parcel.getId() + " " + parcel.getPaymentContract().getId() + " " + parcel.getPayloadContract().getId());

        normalClient.registerParcel(parcel.pack(),8000);
        synchronized (tuContractLock) {
            tuContract = parcel.getPaymentContract();
        }
        return parcel;
    }

    protected synchronized Parcel registerWithNewParcel(TransactionPack tp) throws Exception {

        Set<PrivateKey> stepaPrivateKeys = new HashSet<>();
        stepaPrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "keys/stepan_mamontov.private.unikey")));

        Contract tu = getApprovedTUContract();
        // stepaPrivateKeys - is also U keys
        Parcel parcel =  ContractsService.createParcel(tp, tu, 150, stepaPrivateKeys);
        System.out.println("-------------");
        normalClient.registerParcel(parcel.pack(),8000);
        synchronized (tuContractLock) {
            tuContract = parcel.getPaymentContract();
        }

        return parcel;
    }

    private synchronized void registerAndCheckApproved(Contract c) throws Exception {
        Parcel parcel = registerWithNewParcel(c);
        waitAndCheckApproved(parcel);
    }

    private synchronized void registerAndCheckApproved(TransactionPack tp) throws Exception {
        Parcel parcel = registerWithNewParcel(tp);
        waitAndCheckApproved(parcel);
    }

    private synchronized void waitAndCheckApproved(Parcel parcel) throws Exception {

        waitAndCheckState(parcel, ItemState.APPROVED);
    }

    private synchronized void registerAndCheckDeclined(Contract c) throws Exception {
        Parcel parcel = registerWithNewParcel(c);
        waitAndCheckDeclined(parcel);
    }

    private synchronized void registerAndCheckDeclined(TransactionPack tp) throws Exception {
        Parcel parcel = registerWithNewParcel(tp);
        waitAndCheckDeclined(parcel);
    }

    private synchronized void waitAndCheckDeclined(Parcel parcel) throws Exception {

        waitAndCheckState(parcel, ItemState.DECLINED);
    }

    private synchronized void waitAndCheckState(Parcel parcel, ItemState waitState) throws Exception {
        int attemps = 30;
        ItemResult itemResult;
        do {
            itemResult = normalClient.getState(parcel.getPaymentContract().getId());
            if(!itemResult.state.isPending())
                break;
            attemps--;
            if(attemps <= 0)
                fail("timeout1, parcel " + parcel.getId() + " " + parcel.getPaymentContract().getId() + " " + parcel.getPayloadContract().getId());
            Thread.sleep(500);
        } while(true);
        assertEquals(ItemState.APPROVED, itemResult.state);

        attemps = 30;
        do {
            itemResult = normalClient.getState(parcel.getPaymentContract().getId());
            if(!itemResult.state.isPending())
                break;
            attemps--;
            if(attemps <= 0)
                fail("timeout2, parcel " + parcel.getId() + " " + parcel.getPaymentContract().getId() + " " + parcel.getPayloadContract().getId());
            Thread.sleep(500);
        } while(true);

        assertEquals(waitState, itemResult.state);
    }

    @Test
    public void registerSlotContract() throws Exception {

        final PrivateKey key = new PrivateKey(Do.read(ROOT_PATH + "_xer0yfe2nn1xthc.private.unikey"));
        Set<PrivateKey> slotIssuerPrivateKeys = new HashSet<>();
        slotIssuerPrivateKeys.add(key);
        Set<PublicKey> slotIssuerPublicKeys = new HashSet<>();
        slotIssuerPublicKeys.add(key.getPublicKey());

        // contract for storing

        Contract simpleContract = new Contract(key);
        simpleContract.seal();
        simpleContract.check();
        simpleContract.traceErrors();
        assertTrue(simpleContract.isOk());

        registerAndCheckApproved(simpleContract);

        // slot contract that storing

        SlotContract slotContract = ContractsService.createSlotContract(slotIssuerPrivateKeys, slotIssuerPublicKeys);
        slotContract.setNodeConfig(config);
        slotContract.putTrackingContract(simpleContract);

        // payment contract
        // will create two revisions in the createPayingParcel, first is pay for register, second is pay for storing

        Contract paymentContract = getApprovedTUContract();

        Set<PrivateKey> stepaPrivateKeys = new HashSet<>();
        stepaPrivateKeys.add(new PrivateKey(Do.read(ROOT_PATH + "keys/stepan_mamontov.private.unikey")));
        Parcel payingParcel = ContractsService.createPayingParcel(slotContract.getTransactionPack(), paymentContract, 1, 100, stepaPrivateKeys, false);

        slotContract.check();
        slotContract.traceErrors();
        assertTrue(slotContract.isOk());

        assertEquals(SmartContract.SmartContractType.SLOT1.name(), slotContract.getDefinition().getExtendedType());
        assertEquals(SmartContract.SmartContractType.SLOT1.name(), slotContract.get("definition.extended_type"));
        assertEquals(100 * Config.kilobytesAndDaysPerU, slotContract.getPrepaidKilobytesForDays());
        System.out.println(">> " + slotContract.getPrepaidKilobytesForDays() + " KD");
        System.out.println(">> " + simpleContract.getPackedTransaction().length / 1024 + " Kb");
        System.out.println(">> " + 100 * Config.kilobytesAndDaysPerU / (simpleContract.getPackedTransaction().length / 1024) + " days");

//        for(Node n : nodes) {
//            n.setVerboseLevel(DatagramAdapter.VerboseLevel.BASE);
//        }
        normalClient.registerParcel(payingParcel.pack(),8000);
        synchronized (tuContractLock) {
            tuContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // check payment and payload contracts
        slotContract.traceErrors();
        assertEquals(ItemState.REVOKED, normalClient.getState(payingParcel.getPayment().getContract().getId()).state);
        assertEquals(ItemState.APPROVED, normalClient.getState(payingParcel.getPayload().getContract().getId()).state);
        assertEquals(ItemState.APPROVED, normalClient.getState(slotContract.getNew().get(0).getId()).state);

        ItemResult itemResult = normalClient.getState(slotContract.getId());
        assertEquals("ok", itemResult.extraDataBinder.getBinder("onCreatedResult").getString("status", null));

        assertEquals(simpleContract.getId(), slotContract.getTrackingContract().getId());
        assertEquals(simpleContract.getId(), ((SlotContract) payingParcel.getPayload().getContract()).getTrackingContract().getId());


        // check if we store same contract as want

        byte[] restoredPackedData = ledger.getContractInStorage(simpleContract.getId());
        assertNotNull(restoredPackedData);
        Contract restoredContract = Contract.fromPackedTransaction(restoredPackedData);
        assertNotNull(restoredContract);
        assertEquals(simpleContract.getId(), restoredContract.getId());

        ZonedDateTime now;

        Set<ContractStorageSubscription> foundCssSet = ledger.getStorageSubscriptionsForContractId(simpleContract.getId());
        if(foundCssSet != null) {
            for (ContractStorageSubscription foundCss : foundCssSet) {
                System.out.println(foundCss.expiresAt());
                now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ZonedDateTime.now().toEpochSecond()), ZoneId.systemDefault());
                assertAlmostSame(now.plusDays(100 * Config.kilobytesAndDaysPerU / (simpleContract.getPackedTransaction().length / 1024)), foundCss.expiresAt());
            }
        } else {
            fail("ContractStorageSubscription was not found");
        }

        // check if we store environment

        byte[] ebytes = ledger.getEnvironmentFromStorage(slotContract.getId());
        assertNotNull(ebytes);
        Binder binder = Boss.unpack(ebytes);
        assertNotNull(binder);


        // refill slot contract with U (means add storing days).

        SlotContract refilledSlotContract = (SlotContract) slotContract.createRevision(key);
        refilledSlotContract.setNodeConfig(config);

        // payment contract
        // will create two revisions in the createPayingParcel, first is pay for register, second is pay for storing

        paymentContract = getApprovedTUContract();

        payingParcel = ContractsService.createPayingParcel(refilledSlotContract.getTransactionPack(), paymentContract, 1, 300, stepaPrivateKeys, false);

        refilledSlotContract.check();
        refilledSlotContract.traceErrors();
        assertTrue(refilledSlotContract.isOk());

        assertEquals(SmartContract.SmartContractType.SLOT1.name(), refilledSlotContract.getDefinition().getExtendedType());
        assertEquals(SmartContract.SmartContractType.SLOT1.name(), refilledSlotContract.get("definition.extended_type"));
        assertEquals((100 + 300) * Config.kilobytesAndDaysPerU, refilledSlotContract.getPrepaidKilobytesForDays());
        now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ZonedDateTime.now().toEpochSecond()), ZoneId.systemDefault());
        System.out.println(">> " + refilledSlotContract.getPrepaidKilobytesForDays() + " KD");
        System.out.println(">> " + simpleContract.getPackedTransaction().length / 1024 + " Kb");
        System.out.println(">> " + (100 + 300) * Config.kilobytesAndDaysPerU / (simpleContract.getPackedTransaction().length / 1024) + " days");

        normalClient.registerParcel(payingParcel.pack(),8000);
        synchronized (tuContractLock) {
            tuContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // check payment and payload contracts
        assertEquals(ItemState.REVOKED, normalClient.getState(payingParcel.getPayment().getContract().getId()).state);
        assertEquals(ItemState.APPROVED, normalClient.getState(payingParcel.getPayload().getContract().getId()).state);
        assertEquals(ItemState.APPROVED, normalClient.getState(refilledSlotContract.getNew().get(0).getId()).state);

        itemResult = normalClient.getState(refilledSlotContract.getId());
        assertEquals("ok", itemResult.extraDataBinder.getBinder("onUpdateResult").getString("status", null));

        foundCssSet = ledger.getStorageSubscriptionsForContractId(simpleContract.getId());
        if(foundCssSet != null) {
            for (ContractStorageSubscription foundCss : foundCssSet) {
                System.out.println(foundCss.expiresAt());
                now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ZonedDateTime.now().toEpochSecond()), ZoneId.systemDefault());
                assertAlmostSame(now.plusDays((100 + 300) * Config.kilobytesAndDaysPerU / (simpleContract.getPackedTransaction().length / 1024)), foundCss.expiresAt());
            }
        } else {
            fail("ContractStorageSubscription was not found");
        }

        // check if we updated environment and subscriptions (remove old, create new)

        assertEquals(ItemState.REVOKED, normalClient.getState(slotContract.getId()).state);
        ebytes = ledger.getEnvironmentFromStorage(slotContract.getId());
        assertNull(ebytes);

        ebytes = ledger.getEnvironmentFromStorage(refilledSlotContract.getId());
        assertNotNull(ebytes);
        binder = Boss.unpack(ebytes);
        assertNotNull(binder);


        // refill slot contract with U again (means add storing days). the oldest revision should removed

        SlotContract refilledSlotContract2 = (SlotContract) refilledSlotContract.createRevision(key);
        refilledSlotContract2.setNodeConfig(config);

        // payment contract
        // will create two revisions in the createPayingParcel, first is pay for register, second is pay for storing

        paymentContract = getApprovedTUContract();

        payingParcel = ContractsService.createPayingParcel(refilledSlotContract2.getTransactionPack(), paymentContract, 1, 300, stepaPrivateKeys, false);

        refilledSlotContract2.check();
        refilledSlotContract2.traceErrors();
        assertTrue(refilledSlotContract2.isOk());

        assertEquals(SmartContract.SmartContractType.SLOT1.name(), refilledSlotContract2.getDefinition().getExtendedType());
        assertEquals(SmartContract.SmartContractType.SLOT1.name(), refilledSlotContract2.get("definition.extended_type"));
        assertEquals((100 + 300 + 300) * Config.kilobytesAndDaysPerU, refilledSlotContract2.getPrepaidKilobytesForDays());
        now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ZonedDateTime.now().toEpochSecond()), ZoneId.systemDefault());
        System.out.println(">> " + refilledSlotContract2.getPrepaidKilobytesForDays() + " KD");
        System.out.println(">> " + simpleContract.getPackedTransaction().length / 1024 + " Kb");
        System.out.println(">> " + (100 + 300 + 300) * Config.kilobytesAndDaysPerU / (simpleContract.getPackedTransaction().length / 1024) + " days");

        normalClient.registerParcel(payingParcel.pack(),8000);
        synchronized (tuContractLock) {
            tuContract = payingParcel.getPayloadContract().getNew().get(0);
        }
        // check payment and payload contracts
        assertEquals(ItemState.REVOKED, normalClient.getState(payingParcel.getPayment().getContract().getId()).state);
        assertEquals(ItemState.APPROVED, normalClient.getState(payingParcel.getPayload().getContract().getId()).state);
        assertEquals(ItemState.APPROVED, normalClient.getState(refilledSlotContract2.getNew().get(0).getId()).state);

        itemResult = normalClient.getState(refilledSlotContract2.getId());
        assertEquals("ok", itemResult.extraDataBinder.getBinder("onUpdateResult").getString("status", null));

        foundCssSet = ledger.getStorageSubscriptionsForContractId(simpleContract.getId());
        if(foundCssSet != null) {
            for (ContractStorageSubscription foundCss : foundCssSet) {
                System.out.println(foundCss.expiresAt());
                now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ZonedDateTime.now().toEpochSecond()), ZoneId.systemDefault());
                assertAlmostSame(now.plusDays((100 + 300 + 300) * Config.kilobytesAndDaysPerU / (simpleContract.getPackedTransaction().length / 1024)), foundCss.expiresAt());
            }
        } else {
            fail("ContractStorageSubscription was not found");
        }

        // check if we updated environment and subscriptions (remove old, create new)

        assertEquals(ItemState.REVOKED, normalClient.getState(slotContract.getId()).state);
        ebytes = ledger.getEnvironmentFromStorage(slotContract.getId());
        assertNull(ebytes);

        ebytes = ledger.getEnvironmentFromStorage(refilledSlotContract.getId());
        assertNull(ebytes);

        ebytes = ledger.getEnvironmentFromStorage(refilledSlotContract2.getId());
        assertNotNull(ebytes);
        binder = Boss.unpack(ebytes);
        assertNotNull(binder);


        // revoke slot contract, means remove stored contract from storage

        Contract revokingSlotContract = ContractsService.createRevocation(refilledSlotContract2, key);

        registerAndCheckApproved(revokingSlotContract);

        itemResult = normalClient.getState(refilledSlotContract2.getId());
        assertEquals(ItemState.REVOKED, itemResult.state);

        // check if we remove stored contract from storage

        restoredPackedData = ledger.getContractInStorage(simpleContract.getId());
        assertNull(restoredPackedData);

        // check if we remove subscriptions

        foundCssSet = ledger.getStorageSubscriptionsForContractId(simpleContract.getId());
        assertNull(foundCssSet);

        // check if we remove environment

        ebytes = ledger.getEnvironmentFromStorage(refilledSlotContract2.getId());
        assertNull(ebytes);
    }


    private static final String uno_flint004_rev1_bin_b64 = "JyNkYXRhxA0GHxtuZXcGQ3Jldm9raW5nHUNjb250cmFjdCdLYXBpX2xldmVsGDNfX3R5cGWDVW5pdmVyc2FDb250cmFjdFNkZWZpbml0aW9uLyNkYXRhF1Npc3N1ZXJOYW1luxdVbml2ZXJzYSBSZXNlcnZlIFN5c3RlbSNuYW1ls3RyYW5zYWN0aW9uIHVuaXRzIHBhY2tTcmVmZXJlbmNlcx1bcGVybWlzc2lvbnMnM0dzX2xYaTeFo2RlY3JlbWVudF9wZXJtaXNzaW9uS21pbl92YWx1ZQAjcm9sZR9bdGFyZ2V0X25hbWUrb3duZXJFQ1JvbGVMaW5rhTNvd25lcjJDbWF4X3N0ZXAKU2ZpZWxkX25hbWWLdHJhbnNhY3Rpb25fdW5pdHNFu0Bjb20uaWNvZGljaS51bml2ZXJzYS5jb250cmFjdC5wZXJtaXNzaW9ucy5DaGFuZ2VOdW1iZXJQZXJtaXNzaW9uM1pBOU50MjeFvRe9GAC9Gb0avR8KvSCzdGVzdF90cmFuc2FjdGlvbl91bml0c0W9IjNDYVZuOXIfvRknI2tleXMOFxtrZXkXRWNSU0FQdWJsaWNLZXkzcGFja2VkxAkBHggcAQABxAABxSSWfXW20wGsRn9khVZDtvcCtUqP/scN3oVPU3r0152Fu62pfx9Mjc1cmQnRYSkeZzWA50RYQTU3FlXC5iIN7w+Lm6TGPQxWe+uYGMgKLCbAmyMXPWupvzeB5SEMtylQ5ml12iwFQkamqOiFE2KWMYz/UGhW87/ELPckmpoanZUa8OGCACUfFGALAZV0G+rQ/8xiW3hkGHmOFP0kejZaCZGPO/XGVay+2q0V2cw6CHar+D9F9FomXYA4bAInlY3zOLHdG8ddUTzhHQWOKmzoF9eIW67U9rd9qIR04U9ls9wGLQchqlG/kxHHfR4Js86mwYNgUKW49fQRppig+SsrjUVLS2V5UmVjb3JkRVNTaW1wbGVSb2xlhTNpc3N1ZXI7YW5vbklkcx1Fq0NoYW5nZU93bmVyUGVybWlzc2lvboVjY2hhbmdlX293bmVyM2JNc09ZMi+FvRe9GAC9GR+9G70zRb0dhTtpc3N1ZXIyvSC9JUW9IlNjcmVhdGVkX2F0eQ8JIVWFvTMnvSkOF70sF0W9Lr0vxAkBHggcAQABxAABxSSWfXW20wGsRn9khVZDtvcCtUqP/scN3oVPU3r0152Fu62pfx9Mjc1cmQnRYSkeZzWA50RYQTU3FlXC5iIN7w+Lm6TGPQxWe+uYGMgKLCbAmyMXPWupvzeB5SEMtylQ5ml12iwFQkamqOiFE2KWMYz/UGhW87/ELPckmpoanZUa8OGCACUfFGALAZV0G+rQ/8xiW3hkGHmOFP0kejZaCZGPO/XGVay+2q0V2cw6CHar+D9F9FomXYA4bAInlY3zOLHdG8ddUTzhHQWOKmzoF9eIW67U9rd9qIR04U9ls9wGLQchqlG/kxHHfR4Js86mwYNgUKW49fQRppig+SsrjUW9MUW9MoW9M700HStzdGF0ZU+9HCe9KQ4XvSwXRb0uvS/ECQEeCBwBAAHEAAGrOi7YKiKv4jCJhXMUN7x7120EL0Q179+YC3kM6ojRavDNmnnGyHCa3HEh6TZim2/bdWsCJeU3k7dlCt09E6421ApyTSt+WDe7xFySu/rVQoVGuXOyw97Oiaq6/NfbzUismNMTrDgWYtGXCGLP4RrwG7wulb7fgwevuuNgTXtn4p01mlrWfGaPR8E+kS9XOXLPDx3OUXNYByYHX5GKOvdFNfOoFYlsf/xEM4Eqa1GsTixEcJ7+OZCn2loVEMxna1DxtD7rorx8tSTWfp6h4qwcmcgXY1RKvsZj0rrf4PwqUhYwkp5cfbE9dqHv525aoHO5k3EdDeRuqodcZOh2QEu9Rb0xRb0yhb0cvTQdM3BhcmVudAVTZXhwaXJlc19hdHkPcCVkhWUXvSEgvSXAECdLYnJhbmNoX2lkBTNvcmlnaW4FvTt5DwkhVYVTY3JlYXRlZF9ieR+9G70zRb0dhTtjcmVhdG9yQ3JldmlzaW9uCCN0eXBlU3VuaWNhcHN1bGU7dmVyc2lvbhhTc2lnbmF0dXJlcw7EkgEXI3NpZ27EAAE3KMYISMZ4FRmlkEPV4VmkSKDom2VNBEiClh9mNwnzF45IHStnS7LGy8i9ZMY5V6gMdbG0hvgrKxVZPTMYD2Yp9De7LKE+E3MlXg2GAY/YaXD5lDeYC+cECCbERlFOOhzg4lWzNnu7Qn+K2SVCvJ61K/dGHlO33vt9GueKO43rwgPg2TxBuaXca0z+dRVZX57l0A9WuwpND9uBx0enYtxazfjMHFpPyWPiCqmFjpRWBQ6hYVevypqKy9RqrrisnM9Cbrh1jU+ERd2wBFAlN4byF7FKRF5DJRt+CX9bdk7p6gkzG6A/YqVzf+gru+JrRsjgJJd/1Rw8u+rI60hkUHynI2V4dHO8gh8zc2hhNTEyvEBI0fCkiSs+6VfbX5k4qN/DBFfCQWiaygnGC3A6ikR/8aJ6GIQGwnG5wjr2CJd9wgurOfKMfmoVZi86sgAzKowXU2NyZWF0ZWRfYXR5DwkhVYUba2V5vCEHucc+TxvM9el1aV8pt25c2FqaAvPHuKqggaGHbxLHTJ0=";
    private static final String uno_flint004_privKey_b64 = "JgAcAQABvIDayzI6N7cLoXiAf826OwDmGbU/RYl0MrhCaRx1dExXJYAMOEnSbgIP5+VkCjbuJqLL8tAIGaatZIHmFBXDg3Ub75Y82spLZ+sCblnpO+lY3f4AXN9unXCiUa44W9ysYEOTiQYxCROohis5A33C/wVt+aMSq2TGMaQuIcTJKkuSnbyAyFhDk7PrnjW6WuFm615F/bIeNZssuUhmBs9zus/05mIIlzRX0tRv1xVNpsUXyKJ8I5MMIxRyIkvD2IOdjJ2CxGO36C2KIze6lZ6r1+hYWaUT10aH5ToxkRS8jZhPTrOshZ0n2kGrDlPLxU8hf3JHHPBMMNEvzmbn0pM8oSaiQ6E=";

}
