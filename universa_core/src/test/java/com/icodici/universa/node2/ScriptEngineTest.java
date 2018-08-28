package com.icodici.universa.node2;

import com.icodici.crypto.KeyAddress;
import com.icodici.universa.HashId;
import com.icodici.universa.contract.Contract;
import com.icodici.universa.contract.jsapi.*;
import com.icodici.universa.contract.jsapi.permissions.JSApiChangeNumberPermission;
import com.icodici.universa.contract.jsapi.permissions.JSApiPermission;
import com.icodici.universa.contract.jsapi.permissions.JSApiSplitJoinPermission;
import com.icodici.universa.contract.jsapi.storage.JSApiStorage;
import com.icodici.universa.contract.permissions.*;
import com.icodici.universa.contract.roles.SimpleRole;
import com.icodici.universa.node.network.TestKeys;
import jdk.nashorn.api.scripting.ClassFilter;
import jdk.nashorn.api.scripting.NashornScriptEngineFactory;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import net.sergeych.tools.Binder;
import net.sergeych.utils.Base64;
import net.sergeych.utils.Bytes;
import org.junit.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.junit.Assert.*;

public class ScriptEngineTest {

    public interface CustomInterface {
        void funcA();
        String funcB(String prm);
    }

    public class CustomClass {
        public void funcA(String prm) {
            System.out.println("CustomClass.funcA("+prm+")");
        }
        public void funcB(HashId hashId) {
            System.out.println("CustomClass.funcB("+hashId.toBase64String()+")");
        }
    }

    class ClassFilter_restrictAll implements ClassFilter {
        @Override
        public boolean exposeToScripts(String s) {
            return false;
        }
    }

    class ClassFilter_allowSomething implements ClassFilter {
        private Set<String> allowedClasses = null;

        public ClassFilter_allowSomething() {
            allowedClasses = new HashSet<>();
            allowedClasses.add("com.icodici.universa.contract.Contract");
            allowedClasses.add("com.icodici.universa.HashId");
            allowedClasses.add("net.sergeych.tools.Do");
        }

        @Override
        public boolean exposeToScripts(String s) {
            if (allowedClasses.contains(s))
                return true;
            return false;
        }
    }

    @Test
    public void putJavaObjectIntoJS() throws Exception {
        try {
            ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_restrictAll());
            jse.put("obj", new CustomClass());
            jse.eval("obj.funcA('text1');");
        } catch (ScriptException e) {
            assertTrue(false);
        }
    }

    @Test
    public void createJavaObjectWithJS() throws Exception {
        try {
            ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_allowSomething());
            jse.put("obj", new CustomClass());
            jse.eval("obj.funcA('text1');");
            jse.eval("var id = com.icodici.universa.HashId.createRandom();");
            jse.eval("obj.funcB(id);");
        } catch (ScriptException e) {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    @Test
    public void createJavaObjectWithJS_restricted() throws Exception {
        try {
            //ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_restrictAll());
            ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(s -> false);
            jse.put("obj", new CustomClass());
            jse.eval("obj.funcA('text1');");
            jse.eval("var id = com.icodici.universa.HashId.createRandom();");
            jse.eval("obj.funcB(id);");
            assertTrue(false);
        } catch (RuntimeException e) {
            System.out.println("restricted access: " + e);
            assertTrue(true);
        }
    }

    @Test
    public void calcSomethingWithJS() throws Exception {
        ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_restrictAll());
        jse.put("a", 33);
        jse.put("b", 44);
        jse.eval("var c = '' + (a + b);");
        String c = (String)jse.get("c");
        assertEquals("77", c);
    }

    @Test
    public void createContractWithJS() throws Exception {
        ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_allowSomething());
        jse.eval("var contract = new com.icodici.universa.contract.Contract();");
        jse.eval("contract.getDefinition().getData().put('someKey', 'someValue');");
        jse.eval("contract.seal()");
        Contract contract = (Contract)jse.get("contract");
        System.out.println("contract id: " + contract.getId());
        System.out.println("contract someKey: " + contract.getDefinition().getData().getString("someKey"));
        assertEquals("someValue", contract.getDefinition().getData().getString("someKey"));
    }

    @Test
    public void implementJavaInterfaceWithJS() throws Exception {
        ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_restrictAll());
        jse.eval("var customInterface = new Object(); customInterface.funcA = function() {print('custom funcA() hit!');}");
        jse.eval("customInterface.funcB = function(prm) {print('custom funcB() hit! prm='+prm); return 'js_'+prm;}");
        CustomInterface customInterfaceInstance = ((Invocable)jse).getInterface(jse.get("customInterface"), CustomInterface.class);
        customInterfaceInstance.funcA();
        String res = customInterfaceInstance.funcB("java");
        assertEquals("js_java", res);
    }

    private String[] prepareTestFile() throws Exception {
        String textToWrite2file = Bytes.random(32).toBase64();
        System.out.println("prepareTestFile with content: " + textToWrite2file);
        String fileName = "javax.test.file.txt";
        String tmpdir = System.getProperty("java.io.tmpdir");
        String strPath = tmpdir + "/" + fileName;
        Files.deleteIfExists(Paths.get(strPath));
        File f = new File(strPath);
        f.createNewFile();
        Files.write(Paths.get(strPath), textToWrite2file.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        String readedText4check = new String(Files.readAllBytes(Paths.get(strPath)));
        assertEquals(readedText4check, textToWrite2file);
        return new String[]{strPath, textToWrite2file};
    }

    @Test
    public void openFileByJavaClass_success() throws Exception {
        String res[] = prepareTestFile();
        String path = res[0];
        String content = res[1];
        ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine();
        jse.put("path", path);
        jse.eval("load('nashorn:mozilla_compat.js');");
        jse.eval("importPackage('java.nio.file');");
        jse.eval("var readed = new java.lang.String(Files.readAllBytes(java.nio.file.Paths.get(path)));");
        jse.eval("print('path: ' + path);");
        jse.eval("print('content: ' + readed);");
        String readed = (String)jse.get("readed");
        assertEquals(readed, content);
    }

    @Test
    public void openFileByJavaClass_restricted() throws Exception {
        String res[] = prepareTestFile();
        String path = res[0];
        String content = res[1];
        ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_restrictAll());
        jse.put("path", path);
        jse.eval("load('nashorn:mozilla_compat.js');");
        jse.eval("importPackage('java.nio.file');");
        try {
            jse.eval("var readed = new java.lang.String(Files.readAllBytes(java.nio.file.Paths.get(path)));");
            assert false;
        } catch (ScriptException e) {
            System.out.println("ScriptException: " + e);
        }
        jse.eval("print('path: ' + path);");
        jse.eval("print('content: ' + readed);");
        String readed = (String)jse.get("readed");
        assertNotEquals(readed, content);
    }

    @Test
    public void openFileByJS_success() throws Exception {
        String res[] = prepareTestFile();
        String path = res[0];
        String content = res[1];
        ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine();
        jse.put("path", path);
        jse.eval("print(typeof String);");
        try {
            jse.eval("var reader = new FileReader();");
            jse.eval("print('path: ' + path);");
            assert false;
        } catch (ScriptException e) {
            System.out.println("ScriptException: " + e);
        }
    }

    @Test
    public void openFileByContract() throws Exception {
        String res[] = prepareTestFile();
        String path = res[0];
        String content = res[1];
        ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_allowSomething());
        jse.put("path", path);
        jse.eval("" +
                "function foo() {" +
                "   try {" +
                "       var contract = com.icodici.universa.contract.Contract.fromSealedFile(path);" +
                "   } catch (err) {" +
                "       print('exception: ' + err);" +
                "   }" +
                "}"
        );
        jse.eval("foo();");
        jse.eval("print('path: ' + path);");
    }

    @Test
    public void openFileByDo_success() throws Exception {
        String res[] = prepareTestFile();
        String path = res[0];
        String content = res[1];
        ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_allowSomething());
        //ScriptEngine jse = new NashornScriptEngineFactory().getScriptEngine(new ClassFilter_restrictAll());
        jse.put("path", path);
        jse.eval("load('nashorn:mozilla_compat.js');");
        jse.eval("importPackage('net.sergeych.tools');");
        jse.eval("function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}");
        jse.eval("" +
                "function foo() {" +
                "   try {" +
                "       var bytes = Do.read(path);" +
                "       var res = bin2string(bytes);" +
                "       print(res);" +
                "       return res;" +
                "   } catch (err) {" +
                "       print('exception: ' + err);" +
                "   }" +
                "}"
        );
        jse.eval("var readed = foo();");
        jse.eval("print('path: ' + path);");
        String readed = (String)jse.get("readed");
        assertEquals(readed, content);
    }

    @Test
    public void jsInContract() throws Exception {
        Contract contract = new Contract(TestKeys.privateKey(0));
        contract.setOwnerKeys(TestKeys.publicKey(1), TestKeys.publicKey(2), TestKeys.publicKey(3));
        contract.setCreatorKeys(TestKeys.publicKey(4), TestKeys.publicKey(5).getLongAddress());
        System.out.println("testKey[10].getShortAddress: " + TestKeys.publicKey(10).getShortAddress().toString());
        System.out.println("testKey[11].getShortAddress: " + TestKeys.publicKey(11).getShortAddress().toString());
        contract.getStateData().set("some_value", HashId.createRandom().toBase64String());
        contract.getStateData().set("some_hash_id", HashId.createRandom());
        String js = "";
        js += "print('hello world');";
        js += "var currentContract = jsApi.getCurrentContract();";
        js += "print('currentContract.getId(): ' + currentContract.getId());";
        js += "print('currentContract.getRevision(): ' + currentContract.getRevision());";
        js += "print('currentContract.getCreatedAt(): ' + currentContract.getCreatedAt());";
        js += "print('currentContract.getOrigin(): ' + currentContract.getOrigin());";
        js += "print('currentContract.getParent(): ' + currentContract.getParent());";
        js += "print('currentContract.getStateDataField(some_value): ' + currentContract.getStateDataField('some_value'));";
        js += "print('currentContract.getStateDataField(some_hash_id): ' + currentContract.getStateDataField('some_hash_id'));";
        js += "print('currentContract.getDefinitionDataField(scripts): ' + currentContract.getDefinitionDataField('scripts'));";
        js += "print('currentContract.getIssuer(): ' + currentContract.getIssuer());";
        js += "print('currentContract.getOwner(): ' + currentContract.getOwner());";
        js += "print('currentContract.getCreator(): ' + currentContract.getCreator());";
        js += "print('call currentContract.setOwner()...');";
        js += "currentContract.setOwner(['ZastWpWNPMqvVJAMocsMUTJg45i8LoC5Msmr7Lt9EaJJRwV2xV', 'a1sxhjdtGhNeji8SWJNPkwV5m6dgWfrQBnhiAxbQwZT6Y5FsXD']);";
        js += "print('currentContract.getOwner(): ' + currentContract.getOwner());";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        contract.execJS(js.getBytes());
    }

    @Test
    public void jsInContract_execZeroParams() throws Exception {
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('jsApiParams.length: ' + jsApiParams.length);";
        js += "result = jsApiParams.length;";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        assertEquals(0, contract.execJS(js.getBytes()));
    }

    @Test
    public void jsInContract_execParams() throws Exception {
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('jsApiParams.length: ' + jsApiParams.length);";
        js += "result = [jsApiParams.length, jsApiParams[0], jsApiParams[1]];";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror) contract.execJS(js.getBytes(), "prm1", "prm2");
        assertEquals(2, res.get("0"));
        assertEquals("prm1", res.get("1"));
        assertEquals("prm2", res.get("2"));
    }

    @Test
    public void extractContractShouldBeRestricted() throws Exception {
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js1 = "";
        js1 += "var c = jsApi.getCurrentContract();";
        js1 += "var rc = c.extractContract(new Object());";
        js1 += "print('extractContract: ' + rc);";
        String js2 = "";
        js2 += "var c = jsApi.getCurrentContract();";
        js2 += "var rc = c.extractContract(null);";
        js2 += "print('extractContract: ' + rc);";
        contract.getState().setJS(js1.getBytes(), "client script 1.js", new JSApiScriptParameters());
        contract.getState().setJS(js2.getBytes(), "client script 2.js", new JSApiScriptParameters());
        contract.seal();
        contract = Contract.fromPackedTransaction(contract.getPackedTransaction());
        try {
            contract.execJS(js1.getBytes());
            assert false;
        } catch (ClassCastException e) {
            System.out.println(e);
            assert true;
        }
        try {
            contract.execJS(js2.getBytes());
            assert false;
        } catch (ClassCastException e) {
            System.out.println(e);
            assert true;
        }
    }

    @Test
    public void twoJsInContract() throws Exception {
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js1d = "var result = 'return_from_script_1d';";
        String js2d = "var result = 'return_from_script_2d';";
        String js1s = "var result = 'return_from_script_1s';";
        String js2s = "var result = 'return_from_script_2s';";
        contract.getDefinition().setJS(js1d.getBytes(), "js1d.js", new JSApiScriptParameters());
        contract.getDefinition().setJS(js2d.getBytes(), "js2d.js", new JSApiScriptParameters());
        contract.getState().setJS(js1s.getBytes(), "js1s.js", new JSApiScriptParameters());
        contract.getState().setJS(js2s.getBytes(), "js2s.js", new JSApiScriptParameters());
        contract.seal();
        assertEquals("return_from_script_1d", contract.execJS(js1d.getBytes()));
        assertEquals("return_from_script_2d", contract.execJS(js2d.getBytes()));
        assertEquals("return_from_script_1s", contract.execJS(js1s.getBytes()));
        assertEquals("return_from_script_2s", contract.execJS(js2s.getBytes()));
        try {
            contract.execJS("print('another script');".getBytes());
            assert false;
        } catch (IllegalArgumentException e) {
            System.out.println(e);
            assert true;
        }
    }

    @Test
    public void fileName2fileKey() throws Exception {
        String in = "some long.file name with.extention";
        String expectedOut = "some_long_file_name_with_extention";
        assertEquals(expectedOut, JSApiHelpers.fileName2fileKey(in));
    }

    @Test
    public void rawJavaScript() throws Exception {
        String fileName = "somescript.js";
        String scriptDump = "cHJpbnQoJ2hlbGxvIHdvcmxkJyk7DQp2YXIgY3VycmVudENvbnRyYWN0ID0ganNBcGkuZ2V0Q3VycmVudENvbnRyYWN0KCk7DQpwcmludCgnY3VycmVudENvbnRyYWN0LmdldElkKCk6ICcgKyBjdXJyZW50Q29udHJhY3QuZ2V0SWQoKSk7DQpwcmludCgnY3VycmVudENvbnRyYWN0LmdldFJldmlzaW9uKCk6ICcgKyBjdXJyZW50Q29udHJhY3QuZ2V0UmV2aXNpb24oKSk7DQpwcmludCgnY3VycmVudENvbnRyYWN0LmdldENyZWF0ZWRBdCgpOiAnICsgY3VycmVudENvbnRyYWN0LmdldENyZWF0ZWRBdCgpKTsNCnByaW50KCdjdXJyZW50Q29udHJhY3QuZ2V0T3JpZ2luKCk6ICcgKyBjdXJyZW50Q29udHJhY3QuZ2V0T3JpZ2luKCkpOw0KcHJpbnQoJ2N1cnJlbnRDb250cmFjdC5nZXRQYXJlbnQoKTogJyArIGN1cnJlbnRDb250cmFjdC5nZXRQYXJlbnQoKSk7DQpwcmludCgnY3VycmVudENvbnRyYWN0LmdldFN0YXRlRGF0YUZpZWxkKHNvbWVfdmFsdWUpOiAnICsgY3VycmVudENvbnRyYWN0LmdldFN0YXRlRGF0YUZpZWxkKCdzb21lX3ZhbHVlJykpOw0KcHJpbnQoJ2N1cnJlbnRDb250cmFjdC5nZXRTdGF0ZURhdGFGaWVsZChzb21lX2hhc2hfaWQpOiAnICsgY3VycmVudENvbnRyYWN0LmdldFN0YXRlRGF0YUZpZWxkKCdzb21lX2hhc2hfaWQnKSk7DQpwcmludCgnY3VycmVudENvbnRyYWN0LmdldERlZmluaXRpb25EYXRhRmllbGQoc2NyaXB0cyk6ICcgKyBjdXJyZW50Q29udHJhY3QuZ2V0RGVmaW5pdGlvbkRhdGFGaWVsZCgnc2NyaXB0cycpKTsNCnByaW50KCdjdXJyZW50Q29udHJhY3QuZ2V0SXNzdWVyKCk6ICcgKyBjdXJyZW50Q29udHJhY3QuZ2V0SXNzdWVyKCkpOw0KcHJpbnQoJ2N1cnJlbnRDb250cmFjdC5nZXRPd25lcigpOiAnICsgY3VycmVudENvbnRyYWN0LmdldE93bmVyKCkpOw0KcHJpbnQoJ2N1cnJlbnRDb250cmFjdC5nZXRDcmVhdG9yKCk6ICcgKyBjdXJyZW50Q29udHJhY3QuZ2V0Q3JlYXRvcigpKTsNCnByaW50KCdjYWxsIGN1cnJlbnRDb250cmFjdC5zZXRPd25lcigpLi4uJyk7DQpjdXJyZW50Q29udHJhY3Quc2V0T3duZXIoWydaYXN0V3BXTlBNcXZWSkFNb2NzTVVUSmc0NWk4TG9DNU1zbXI3THQ5RWFKSlJ3VjJ4VicsICdhMXN4aGpkdEdoTmVqaThTV0pOUGt3VjVtNmRnV2ZyUUJuaGlBeGJRd1pUNlk1RnNYRCddKTsNCnByaW50KCdjdXJyZW50Q29udHJhY3QuZ2V0T3duZXIoKTogJyArIGN1cnJlbnRDb250cmFjdC5nZXRPd25lcigpKTsNCnJlc3VsdCA9IGpzQXBpUGFyYW1zWzBdICsganNBcGlQYXJhbXNbMV07DQo=";
        Contract contract = new Contract(TestKeys.privateKey(0));
        contract.getStateData().set("some_value", HashId.createRandom().toBase64String());
        contract.getStateData().set("some_hash_id", HashId.createRandom());
        contract.getDefinition().setJS(Base64.decodeLines(scriptDump), fileName, new JSApiScriptParameters());
        contract.seal();
        String res = (String)contract.execJS(Base64.decodeLines(scriptDump), "3", "6");
        System.out.println("res: " + res);
        assertEquals("36", res);
        String compression = contract.getDefinition().getData().getOrThrow("scripts", JSApiHelpers.fileName2fileKey(fileName), "compression");
        System.out.println("compression: " + compression);
        assertEquals(JSApiCompressionEnum.RAW, JSApiCompressionEnum.valueOf(compression));
    }

    @Test
    public void compressedJavaScript() throws Exception {
        String fileName = "somescript.zip";
        String scriptDump = "UEsDBBQAAgAIAEGVA02XbF8YbAEAAPoEAAANAAAAc29tZXNjcmlwdC5qc62UXU+DMBSG7038D9yVRUOckTk1XkzmzMiY+xJ0y7JU6KATKLYF9vNlyj6cUtR42/O+z3vSntOI4pDLwEO+T6SUUN8BlavDgwRSyY4pRSHXSMgptLl0LS1YI8KKi7j2uSSvLNEHac+1UrcduXIpAelIKiiK7QOUYIZJKIBsJWKURhHkyGlwAWtHI4bdU+xiUVdrgRjTg6sTAWYtEGOGPOu6CTlsYeQ7MiMBmiXQj1ExeM8Cth7whzAPMm+GnV/G5a6ywCaa4xDz7Il3Um2KI86KA78zgdxVFthmLEZUNLe5oGRG0lBIyes/mFpCy2aW7IGg73/Rsk2koijvm16omIAxZNyKrG7PeE1MvWEQmxkPI909U3G9QzTVYAE97/CLW6jrg9Q8XZrgWAKwypbewuF3XhctcH1o6d3eS2qqQc1xrTnt34Qebiyf++l4VHtSW+yxCab/dokUsdjffFXZ5sCATU6mmW/3oDrNpG9QSwECFAAUAAIACABBlQNNl2xfGGwBAAD6BAAADQAAAAAAAAAAACAAAAAAAAAAc29tZXNjcmlwdC5qc1BLBQYAAAAAAQABADsAAACXAQAAAAA=";
        Contract contract = new Contract(TestKeys.privateKey(0));
        contract.getStateData().set("some_value", HashId.createRandom().toBase64String());
        contract.getStateData().set("some_hash_id", HashId.createRandom());
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        scriptParameters.isCompressed = true;
        contract.getDefinition().setJS(Base64.decodeLines(scriptDump), fileName, scriptParameters);
        contract.seal();
        String res = (String)contract.execJS(Base64.decodeLines(scriptDump), "3", "6");
        System.out.println("res: " + res);
        assertEquals("36", res);
        String compression = contract.getDefinition().getData().getOrThrow("scripts", JSApiHelpers.fileName2fileKey(fileName), "compression");
        System.out.println("compression: " + compression);
        assertEquals(JSApiCompressionEnum.ZIP, JSApiCompressionEnum.valueOf(compression));
    }

    @Test
    public void jsApiTimeLimit() throws Exception {
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "function hardWork(ms) {";
        js += "  var unixtime_ms = new Date().getTime();";
        js += "  while(new Date().getTime() < unixtime_ms + ms) {}";
        js += "}";
        js += "print('jsApiTimeLimit');";
        js += "print('start hardWork...');";
        js += "hardWork(10000);";
        js += "print('hardWork time is up');";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        scriptParameters.timeLimitMillis= 500;
        contract.getDefinition().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        try {
            contract.execJS(js.getBytes());
            assert false;
        } catch (InterruptedException e) {
            System.out.println("InterruptedException: " + e);
            assert true;
        }
    }

    @Test
    public void testSimpleRole() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        KeyAddress k1 = TestKeys.publicKey(1).getShortAddress();
        KeyAddress k2 = TestKeys.publicKey(2).getShortAddress();
        KeyAddress k3 = TestKeys.publicKey(3).getShortAddress();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testSimpleRole');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"', '"+k1.toString()+"', '"+k2.toString()+"');";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        js += "result = simpleRole.getAllAddresses();";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        contract.getDefinition().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        List<String> res = (List<String>)contract.execJS(js.getBytes());
        assertTrue(res.contains(k0.toString()));
        assertTrue(res.contains(k1.toString()));
        assertTrue(res.contains(k2.toString()));
        assertFalse(res.contains(k3.toString()));
    }

    @Test
    public void testSimpleRoleCheck() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        KeyAddress k1 = TestKeys.publicKey(1).getShortAddress();
        KeyAddress k2 = TestKeys.publicKey(2).getShortAddress();
        String p0 = TestKeys.publicKey(0).packToBase64String();
        String p1 = TestKeys.publicKey(1).packToBase64String();
        String p2 = TestKeys.publicKey(2).packToBase64String();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testSimpleRoleCheck');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"', '"+k1.toString()+"', '"+k2.toString()+"');";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        js += "var check0 = simpleRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'), jsApi.base64toPublicKey('"+p1+"'));";
        js += "var check1 = simpleRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'), jsApi.base64toPublicKey('"+p1+"'), jsApi.base64toPublicKey('"+p2+"'));";
        js += "print('check0: ' + check0);";
        js += "print('check1: ' + check1);";
        js += "result = [check0, check1];";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        contract.getDefinition().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(js.getBytes());
        assertFalse((boolean)res.get("0"));
        assertTrue((boolean)res.get("1"));
    }

    @Test
    public void testListRole() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        KeyAddress k1 = TestKeys.publicKey(1).getShortAddress();
        KeyAddress k2 = TestKeys.publicKey(2).getShortAddress();
        KeyAddress k3 = TestKeys.publicKey(3).getShortAddress();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testListRole');";
        js += "var simpleRole0 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var simpleRole1 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k1.toString()+"');";
        js += "var simpleRole2 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k2.toString()+"');";
        js += "print('simpleRole0: ' + simpleRole0.getAllAddresses());";
        js += "print('simpleRole1: ' + simpleRole1.getAllAddresses());";
        js += "print('simpleRole2: ' + simpleRole2.getAllAddresses());";
        js += "var listRole = jsApi.getRoleBuilder().createListRole('listRole', 'all', simpleRole0, simpleRole1, simpleRole2);";
        js += "print('listRole: ' + listRole.getAllAddresses());";
        js += "result = listRole.getAllAddresses();";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        contract.getDefinition().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        List<String> res = (List<String>)contract.execJS(js.getBytes());
        assertTrue(res.contains(k0.toString()));
        assertTrue(res.contains(k1.toString()));
        assertTrue(res.contains(k2.toString()));
        assertFalse(res.contains(k3.toString()));
    }

    @Test
    public void testListRoleCheckAll() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        KeyAddress k1 = TestKeys.publicKey(1).getLongAddress();
        KeyAddress k2 = TestKeys.publicKey(2).getShortAddress();
        KeyAddress k3 = TestKeys.publicKey(3).getLongAddress();
        String p0 = TestKeys.publicKey(0).packToBase64String();
        String p1 = TestKeys.publicKey(1).packToBase64String();
        String p2 = TestKeys.publicKey(2).packToBase64String();
        String p3 = TestKeys.publicKey(3).packToBase64String();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testListRoleCheckAll');";
        js += "var simpleRole0 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var simpleRole1 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k1.toString()+"');";
        js += "var simpleRole2 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k2.toString()+"');";
        js += "var simpleRole3 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k3.toString()+"');";
        js += "print('simpleRole0: ' + simpleRole0.getAllAddresses());";
        js += "print('simpleRole1: ' + simpleRole1.getAllAddresses());";
        js += "print('simpleRole2: ' + simpleRole2.getAllAddresses());";
        js += "print('simpleRole3: ' + simpleRole3.getAllAddresses());";
        js += "var listSubRole = jsApi.getRoleBuilder().createListRole('listRole', 'all', simpleRole2, simpleRole3);";
        js += "var listRole = jsApi.getRoleBuilder().createListRole('listRole', 'all', simpleRole0, simpleRole1, listSubRole);";
        js += "print('listRole: ' + listRole.getAllAddresses());";
        js += "var check0 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'), jsApi.base64toPublicKey('"+p1+"'), jsApi.base64toPublicKey('"+p2+"'));";
        js += "var check1 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'), jsApi.base64toPublicKey('"+p1+"'), jsApi.base64toPublicKey('"+p2+"'), jsApi.base64toPublicKey('"+p3+"'));";
        js += "print('check0: ' + check0);";
        js += "print('check1: ' + check1);";
        js += "result = [check0, check1];";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        contract.getDefinition().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(js.getBytes());
        assertFalse((boolean)res.get("0"));
        assertTrue((boolean)res.get("1"));
    }

    @Test
    public void testListRoleCheckAny() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getLongAddress();
        KeyAddress k1 = TestKeys.publicKey(1).getShortAddress();
        KeyAddress k2 = TestKeys.publicKey(2).getLongAddress();
        KeyAddress k3 = TestKeys.publicKey(3).getShortAddress();
        String p0 = TestKeys.publicKey(0).packToBase64String();
        String p1 = TestKeys.publicKey(1).packToBase64String();
        String p2 = TestKeys.publicKey(2).packToBase64String();
        String p3 = TestKeys.publicKey(3).packToBase64String();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testListRoleCheckAny');";
        js += "var simpleRole0 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var simpleRole1 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k1.toString()+"');";
        js += "var simpleRole2 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k2.toString()+"');";
        js += "var simpleRole3 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k3.toString()+"');";
        js += "print('simpleRole0: ' + simpleRole0.getAllAddresses());";
        js += "print('simpleRole1: ' + simpleRole1.getAllAddresses());";
        js += "print('simpleRole2: ' + simpleRole2.getAllAddresses());";
        js += "print('simpleRole3: ' + simpleRole3.getAllAddresses());";
        js += "var listSubRole = jsApi.getRoleBuilder().createListRole('listRole', 'all', simpleRole2, simpleRole3);";
        js += "var listRole = jsApi.getRoleBuilder().createListRole('listRole', 'any', simpleRole0, simpleRole1, listSubRole);";
        js += "print('listRole: ' + listRole.getAllAddresses());";
        js += "var check0 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'));";
        js += "var check1 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p1+"'));";
        js += "var check2 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p2+"'));";
        js += "var check3 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p3+"'));";
        js += "var check4 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p3+"'), jsApi.base64toPublicKey('"+p2+"'));";
        js += "print('check0: ' + check0);";
        js += "print('check1: ' + check1);";
        js += "print('check2: ' + check2);";
        js += "print('check3: ' + check3);";
        js += "print('check4: ' + check4);";
        js += "result = [check0, check1, check2, check3, check4];";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        contract.getDefinition().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(js.getBytes());
        assertTrue((boolean)res.get("0"));
        assertTrue((boolean)res.get("1"));
        assertFalse((boolean)res.get("2"));
        assertFalse((boolean)res.get("3"));
        assertTrue((boolean)res.get("4"));
    }

    @Test
    public void testListRoleCheckQuorum() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        KeyAddress k1 = TestKeys.publicKey(1).getShortAddress();
        KeyAddress k2 = TestKeys.publicKey(2).getLongAddress();
        KeyAddress k3 = TestKeys.publicKey(3).getLongAddress();
        String p0 = TestKeys.publicKey(0).packToBase64String();
        String p1 = TestKeys.publicKey(1).packToBase64String();
        String p2 = TestKeys.publicKey(2).packToBase64String();
        String p3 = TestKeys.publicKey(3).packToBase64String();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testListRoleCheckQuorum');";
        js += "var simpleRole0 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var simpleRole1 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k1.toString()+"');";
        js += "var simpleRole2 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k2.toString()+"');";
        js += "var simpleRole3 = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k3.toString()+"');";
        js += "print('simpleRole0: ' + simpleRole0.getAllAddresses());";
        js += "print('simpleRole1: ' + simpleRole1.getAllAddresses());";
        js += "print('simpleRole2: ' + simpleRole2.getAllAddresses());";
        js += "print('simpleRole3: ' + simpleRole3.getAllAddresses());";
        js += "var listSubRole = jsApi.getRoleBuilder().createListRole('listRole', 'all', simpleRole2, simpleRole3);";
        js += "var listRole = jsApi.getRoleBuilder().createListRole('listRole', 'any', simpleRole0, simpleRole1, listSubRole);";
        js += "listRole.setQuorum(2);";
        js += "print('listRole: ' + listRole.getAllAddresses());";
        js += "var check0 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'));";
        js += "var check1 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p1+"'));";
        js += "var check2 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p2+"'));";
        js += "var check3 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p3+"'));";
        js += "var check4 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p3+"'), jsApi.base64toPublicKey('"+p2+"'));";
        js += "var check5 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'), jsApi.base64toPublicKey('"+p1+"'));";
        js += "var check6 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'), jsApi.base64toPublicKey('"+p2+"'), jsApi.base64toPublicKey('"+p3+"'));";
        js += "var check7 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p1+"'), jsApi.base64toPublicKey('"+p2+"'), jsApi.base64toPublicKey('"+p3+"'));";
        js += "var check8 = listRole.isAllowedForKeys(jsApi.base64toPublicKey('"+p1+"'), jsApi.base64toPublicKey('"+p2+"'));";
        js += "print('check0: ' + check0);";
        js += "print('check1: ' + check1);";
        js += "print('check2: ' + check2);";
        js += "print('check3: ' + check3);";
        js += "print('check4: ' + check4);";
        js += "print('check5: ' + check5);";
        js += "print('check6: ' + check6);";
        js += "print('check7: ' + check7);";
        js += "print('check8: ' + check8);";
        js += "result = [check0, check1, check2, check3, check4, check5, check6, check7, check8];";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        contract.getDefinition().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(js.getBytes());
        assertFalse((boolean)res.get("0"));
        assertFalse((boolean)res.get("1"));
        assertFalse((boolean)res.get("2"));
        assertFalse((boolean)res.get("3"));
        assertFalse((boolean)res.get("4"));
        assertTrue((boolean)res.get("5"));
        assertTrue((boolean)res.get("6"));
        assertTrue((boolean)res.get("7"));
        assertFalse((boolean)res.get("8"));
    }

    @Test
    public void testRoleLink() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        KeyAddress k1 = TestKeys.publicKey(1).getShortAddress();
        KeyAddress k2 = TestKeys.publicKey(2).getShortAddress();
        String p0 = TestKeys.publicKey(0).packToBase64String();
        String p1 = TestKeys.publicKey(1).packToBase64String();
        String p2 = TestKeys.publicKey(2).packToBase64String();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testRoleLink');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var simpleRole1 = jsApi.getRoleBuilder().createSimpleRole('k1', '"+k1.toString()+"');";
        js += "var simpleRole2 = jsApi.getRoleBuilder().createSimpleRole('k2', '"+k2.toString()+"');";
        js += "var listRole = jsApi.getRoleBuilder().createListRole('issuer', 'all', simpleRole1, simpleRole2);";
        js += "var roleLink0 = jsApi.getRoleBuilder().createRoleLink('link0', 'owner');";
        js += "var roleLink1 = jsApi.getRoleBuilder().createRoleLink('link1', 'link0');";
        js += "var roleLink2 = jsApi.getRoleBuilder().createRoleLink('link2', 'issuer');";
        js += "var roleLink3 = jsApi.getRoleBuilder().createRoleLink('link3', 'link2');";
        js += "jsApi.getCurrentContract().registerRole(simpleRole);";
        js += "jsApi.getCurrentContract().registerRole(listRole);";
        js += "jsApi.getCurrentContract().registerRole(roleLink0);";
        js += "jsApi.getCurrentContract().registerRole(roleLink1);";
        js += "jsApi.getCurrentContract().registerRole(roleLink2);";
        js += "jsApi.getCurrentContract().registerRole(roleLink3);";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        js += "print('listRole: ' + listRole.getAllAddresses());";
        js += "print('roleLink0: ' + roleLink0.getAllAddresses());";
        js += "print('roleLink1: ' + roleLink1.getAllAddresses());";
        js += "print('roleLink2: ' + roleLink2.getAllAddresses());";
        js += "print('roleLink3: ' + roleLink3.getAllAddresses());";
        js += "var check0 = roleLink0.isAllowedForKeys(jsApi.base64toPublicKey('"+p0+"'));";
        js += "var check1 = roleLink1.isAllowedForKeys(jsApi.base64toPublicKey('"+p1+"'));";
        js += "var check2 = roleLink2.isAllowedForKeys(jsApi.base64toPublicKey('"+p2+"'));";
        js += "var check3 = roleLink2.isAllowedForKeys(jsApi.base64toPublicKey('"+p1+"'), jsApi.base64toPublicKey('"+p2+"'));";
        js += "print('check0: ' + check0);";
        js += "print('check1: ' + check1);";
        js += "print('check2: ' + check2);";
        js += "print('check3: ' + check3);";
        js += "result = [check0, check1, check2, check3];";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(js.getBytes());
        assertTrue((boolean)res.get("0"));
        assertFalse((boolean)res.get("1"));
        assertFalse((boolean)res.get("2"));
        assertTrue((boolean)res.get("3"));
    }

    @Test
    public void testSplitJoinPermission() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        String p0 = TestKeys.publicKey(0).packToBase64String();
        String p1 = TestKeys.publicKey(1).packToBase64String();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testSplitJoinPermission');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var splitJoinPermission = jsApi.getPermissionBuilder().createSplitJoinPermission(simpleRole, " +
                "{field_name: 'testval', min_value: 33, min_unit: 1e-7, join_match_fields: ['state.origin']}" +
                ");";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        js += "jsApi.getCurrentContract().addPermission(splitJoinPermission);";
        js += "var isPermitted0 = jsApi.getCurrentContract().isPermitted('split_join', jsApi.base64toPublicKey('"+p0+"'));";
        js += "var isPermitted1 = jsApi.getCurrentContract().isPermitted('split_join', jsApi.base64toPublicKey('"+p1+"'));";
        js += "print('isPermitted0: ' + isPermitted0);";
        js += "print('isPermitted1: ' + isPermitted1);";
        js += "result = [splitJoinPermission, isPermitted0, isPermitted1];";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(js.getBytes());
        SplitJoinPermission splitJoinPermission = (SplitJoinPermission)((JSApiSplitJoinPermission)res.get("0")).extractPermission(new JSApiAccessor());
        SplitJoinPermission sample = new SplitJoinPermission(new SimpleRole("test"), Binder.of(
                "field_name", "testval", "min_value", 33, "min_unit", 1e-7));

        Field field = SplitJoinPermission.class.getDeclaredField("fieldName");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(splitJoinPermission));

        field = SplitJoinPermission.class.getDeclaredField("minValue");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(splitJoinPermission));

        field = SplitJoinPermission.class.getDeclaredField("minUnit");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(splitJoinPermission));

        field = SplitJoinPermission.class.getDeclaredField("mergeFields");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(splitJoinPermission));

        assertTrue((boolean)res.get("1"));
        assertFalse((boolean)res.get("2"));
    }

    @Test
    public void testChangeNumberPermission() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        String p0 = TestKeys.publicKey(0).packToBase64String();
        String p1 = TestKeys.publicKey(1).packToBase64String();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testChangeNumberPermission');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var changeNumberPermission = jsApi.getPermissionBuilder().createChangeNumberPermission(simpleRole, " +
                "{field_name: 'testval', min_value: 44, max_value: 55, min_step: 1, max_step: 2}" +
                ");";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        js += "jsApi.getCurrentContract().addPermission(changeNumberPermission);";
        js += "var isPermitted0 = jsApi.getCurrentContract().isPermitted('decrement_permission', jsApi.base64toPublicKey('"+p0+"'));";
        js += "var isPermitted1 = jsApi.getCurrentContract().isPermitted('decrement_permission', jsApi.base64toPublicKey('"+p1+"'));";
        js += "print('isPermitted0: ' + isPermitted0);";
        js += "print('isPermitted1: ' + isPermitted1);";
        js += "result = [changeNumberPermission, isPermitted0, isPermitted1];";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(js.getBytes());
        ChangeNumberPermission changeNumberPermission = (ChangeNumberPermission)((JSApiChangeNumberPermission)res.get("0")).extractPermission(new JSApiAccessor());
        ChangeNumberPermission sample = new ChangeNumberPermission(new SimpleRole("test"), Binder.of(
                "field_name", "testval", "min_value", 44, "max_value", 55, "min_step", 1, "max_step", 2));

        Field field = ChangeNumberPermission.class.getDeclaredField("fieldName");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(changeNumberPermission));

        field = ChangeNumberPermission.class.getDeclaredField("minValue");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(changeNumberPermission));

        field = ChangeNumberPermission.class.getDeclaredField("maxValue");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(changeNumberPermission));

        field = ChangeNumberPermission.class.getDeclaredField("minStep");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(changeNumberPermission));

        field = ChangeNumberPermission.class.getDeclaredField("maxStep");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(changeNumberPermission));

        assertTrue((boolean)res.get("1"));
        assertFalse((boolean)res.get("2"));
    }

    @Test
    public void testChangeOwnerPermission() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testChangeOwnerPermission');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var changeOwnerPermission = jsApi.getPermissionBuilder().createChangeOwnerPermission(simpleRole);";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        js += "result = changeOwnerPermission;";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        JSApiPermission res = (JSApiPermission) contract.execJS(js.getBytes());
        ChangeOwnerPermission changeOwnerPermission = (ChangeOwnerPermission)res.extractPermission(new JSApiAccessor());
        ChangeOwnerPermission sample = new ChangeOwnerPermission(new SimpleRole("test"));

        Field field = Permission.class.getDeclaredField("name");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(changeOwnerPermission));
    }

    @Test
    public void testModifyDataPermission() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testModifyDataPermission');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var modifyDataPermission = jsApi.getPermissionBuilder().createModifyDataPermission(simpleRole, " +
                "{some_field: [1, 2, 3]});";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        js += "result = modifyDataPermission;";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        JSApiPermission res = (JSApiPermission) contract.execJS(js.getBytes());
        ModifyDataPermission changeOwnerPermission = (ModifyDataPermission)res.extractPermission(new JSApiAccessor());
        ModifyDataPermission sample = new ModifyDataPermission(new SimpleRole("test"), Binder.of("fields", Binder.of("some_field", Arrays.asList(1, 2, 3))));

        Field field = Permission.class.getDeclaredField("name");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(changeOwnerPermission));

        field = ModifyDataPermission.class.getDeclaredField("fields");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(changeOwnerPermission));
    }

    @Test
    public void testRevokePermission() throws Exception {
        KeyAddress k0 = TestKeys.publicKey(0).getShortAddress();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testRevokePermission');";
        js += "var simpleRole = jsApi.getRoleBuilder().createSimpleRole('owner', '"+k0.toString()+"');";
        js += "var revokePermission = jsApi.getPermissionBuilder().createRevokePermission(simpleRole);";
        js += "print('simpleRole: ' + simpleRole.getAllAddresses());";
        js += "result = revokePermission;";
        contract.getDefinition().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        JSApiPermission res = (JSApiPermission) contract.execJS(js.getBytes());
        RevokePermission revokePermission = (RevokePermission)res.extractPermission(new JSApiAccessor());
        RevokePermission sample = new RevokePermission(new SimpleRole("test"));

        Field field = Permission.class.getDeclaredField("name");
        field.setAccessible(true);
        assertEquals(field.get(sample), field.get(revokePermission));
    }

    @Test
    public void testTransactionalAccess() throws Exception {
        String t1value = "t1value";
        String t2value = "t2value";
        Contract contract = new Contract(TestKeys.privateKey(0));
        contract.getTransactionalData().set("t1", t1value);
        String js = "";
        js += "print('testTransactionalAccess');";
        js += "var t1 = jsApi.getCurrentContract().getTransactionalDataField('t1');";
        js += "print('t1: ' + t1);";
        js += "jsApi.getCurrentContract().setTransactionalDataField('t2', '"+t2value+"');";
        js += "result = t1;";
        contract.getState().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        String res = (String)contract.execJS(js.getBytes());
        assertEquals(t1value, res);
        assertEquals(t2value, contract.getTransactionalData().getStringOrThrow("t2"));
        System.out.println("t2: " + contract.getTransactionalData().getStringOrThrow("t2"));
    }

    private List<String> prepareSharedFoldersForTest(String f1content, String f2content, String f3content) throws Exception {
        String tmpdir = System.getProperty("java.io.tmpdir");
        String strPath1 = tmpdir + "/" + "sharedTest1";
        String strPath2 = tmpdir + "/" + "sharedTest2";
        File strPath1File = new File(strPath1);
        File strPath2File = new File(strPath2);
        strPath1File.mkdirs();
        strPath2File.mkdirs();
        File f1 = new File(strPath1 + "/f1.txt");
        File f2 = new File(strPath2 + "/folder/f2.txt");
        File f3 = new File(tmpdir + "/f3.txt");
        f1.delete();
        f2.delete();
        f3.delete();
        f1.getParentFile().mkdirs();
        f2.getParentFile().mkdirs();
        f3.getParentFile().mkdirs();
        f1.createNewFile();
        f2.createNewFile();
        f3.createNewFile();
        Files.write(f1.toPath(), f1content.getBytes());
        Files.write(f2.toPath(), f2content.getBytes());
        Files.write(f3.toPath(), f3content.getBytes());
        List<String> res = new ArrayList<>();
        res.add(strPath1);
        res.add(strPath2);
        return res;
    }

    @Test
    public void testSharedFolders_read() throws Exception {
        String f1content = "f1 content";
        String f2content = "f2 content";
        String f3content = "f3 content";
        List<String> sharedFolders = prepareSharedFoldersForTest(f1content, f2content, f3content);
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testSharedFolders_read');";
        js += "function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}";
        js += "var file1Content = jsApi.getSharedFolders().readAllBytes('f1.txt');";
        js += "var file2Content = jsApi.getSharedFolders().readAllBytes('folder/f2.txt');";
        js += "print('file1Content: ' + bin2string(file1Content));";
        js += "print('file2Content: ' + bin2string(file2Content));";
        js += "result = [bin2string(file1Content), bin2string(file2Content)];";
        JSApiExecOptions execOptions = new JSApiExecOptions();
        execOptions.sharedFolders.addAll(sharedFolders);
        contract.getState().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(execOptions, js.getBytes());
        assertEquals(f1content, res.get("0"));
        assertEquals(f2content, res.get("1"));
    }

    @Test
    public void testSharedFolders_restrictedPath() throws Exception {
        String f1content = "f1 content";
        String f2content = "f2 content";
        String f3content = "f3 content";
        List<String> sharedFolders = prepareSharedFoldersForTest(f1content, f2content, f3content);
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testSharedFolders_restrictedPath');";
        js += "function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}";
        js += "try {";
        js += "  var file3Content = jsApi.getSharedFolders().readAllBytes('../f3.txt');";
        js += "  print('file3Content: ' + bin2string(file3Content));";
        js += "} catch (err) {";
        js += "  result = err;";
        js += "}";
        JSApiExecOptions execOptions = new JSApiExecOptions();
        execOptions.sharedFolders.addAll(sharedFolders);
        contract.getState().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        IOException res = (IOException) contract.execJS(execOptions, js.getBytes());
        System.out.println("IOException from js: " + res);
        assertTrue(res.toString().contains("file '../f3.txt' not found in shared folders"));
    }

    @Test
    public void testSharedFolders_write() throws Exception {
        String f1content = "f1 content";
        String f2content = "f2 content";
        String f3content = "f3 content";
        String f4content = "f4 content";
        List<String> sharedFolders = prepareSharedFoldersForTest(f1content, f2content, f3content);
        Paths.get(sharedFolders.get(0) + "/folder2/f4.txt").toFile().delete();
        Paths.get(sharedFolders.get(0) + "/folder2/f4.txt").toFile().getParentFile().delete();
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testSharedFolders_write');";
        js += "var file4Content = '"+f4content+"';";
        js += "jsApi.getSharedFolders().writeNewFile('folder2/f4.txt', jsApi.string2bin(file4Content));";
        JSApiExecOptions execOptions = new JSApiExecOptions();
        execOptions.sharedFolders.addAll(sharedFolders);
        contract.getState().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        contract.execJS(execOptions, js.getBytes());
        String f4readed = new String(Files.readAllBytes(Paths.get(sharedFolders.get(0) + "/folder2/f4.txt")));
        System.out.println("f4: " + f4readed);
        assertEquals(f4content, f4readed);
    }

    @Test
    public void testSharedFolders_rewrite() throws Exception {
        String f1content = "f1 content";
        String f1contentUpdated = "f1 content updated";
        String f2content = "f2 content";
        String f2contentUpdated = "f2 content updated";
        String f3content = "f3 content";
        List<String> sharedFolders = prepareSharedFoldersForTest(f1content, f2content, f3content);
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testSharedFolders_rewrite');";
        js += "jsApi.getSharedFolders().rewriteExistingFile('f1.txt', jsApi.string2bin('"+f1contentUpdated+"'));";
        js += "jsApi.getSharedFolders().rewriteExistingFile('folder/f2.txt', jsApi.string2bin('"+f2contentUpdated+"'));";
        JSApiExecOptions execOptions = new JSApiExecOptions();
        execOptions.sharedFolders.addAll(sharedFolders);
        contract.getState().setJS(js.getBytes(), "client script.js", new JSApiScriptParameters());
        contract.seal();
        contract.execJS(execOptions, js.getBytes());
        String f1readed = new String(Files.readAllBytes(Paths.get(sharedFolders.get(0) + "/f1.txt")));
        String f2readed = new String(Files.readAllBytes(Paths.get(sharedFolders.get(1) + "/folder/f2.txt")));
        assertNotEquals(f1content, f1readed);
        assertNotEquals(f2content, f2readed);
        assertEquals(f1contentUpdated, f1readed);
        assertEquals(f2contentUpdated, f2readed);
    }

    @Test
    public void testSharedStorage() throws Exception {
        String sharedStoragePath = JSApiStorage.getSharedStoragePath();
        String testFileName = "./someFolder/file1.txt";
        Paths.get(sharedStoragePath + testFileName).toFile().delete();
        String testString1 = "testString1_" + HashId.createRandom().toBase64String();
        String testString2 = "testString2_" + HashId.createRandom().toBase64String();
        System.out.println("testString1: " + testString1);
        System.out.println("testString2: " + testString2);
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testSharedStorage');";
        js += "function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}";
        js += "var sharedStorage = jsApi.getSharedStorage();";
        js += "sharedStorage.writeNewFile('"+testFileName+"', jsApi.string2bin('"+testString1+"'));";
        js += "var file1readed = bin2string(sharedStorage.readAllBytes('"+testFileName+"'));";
        js += "sharedStorage.rewriteExistingFile('"+testFileName+"', jsApi.string2bin('"+testString2+"'));";
        js += "var file2readed = bin2string(sharedStorage.readAllBytes('"+testFileName+"'));";
        js += "var result = [file1readed, file2readed]";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        scriptParameters.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_STORAGE, true);
        contract.getState().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(new JSApiExecOptions(), js.getBytes());
        assertEquals(testString1, res.get("0"));
        assertEquals(testString2, res.get("1"));
    }

    @Test
    public void testOriginStorage() throws Exception {
        String originStoragePath = JSApiStorage.getOriginStoragePath();
        String testFileName = "./someFolder/file1.txt";
        String testString1 = "testString1_" + HashId.createRandom().toBase64String();
        String testString2 = "testString2_" + HashId.createRandom().toBase64String();
        System.out.println("testString1: " + testString1);
        System.out.println("testString2: " + testString2);
        Contract contract = new Contract(TestKeys.privateKey(0));
        String js = "";
        js += "print('testOriginStorage');";
        js += "function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}";
        js += "var originStorage = jsApi.getOriginStorage();";
        js += "originStorage.writeNewFile('"+testFileName+"', jsApi.string2bin('"+testString1+"'));";
        js += "var file1readed = bin2string(originStorage.readAllBytes('"+testFileName+"'));";
        js += "originStorage.rewriteExistingFile('"+testFileName+"', jsApi.string2bin('"+testString2+"'));";
        js += "var file2readed = bin2string(originStorage.readAllBytes('"+testFileName+"'));";
        js += "var result = [file1readed, file2readed]";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        scriptParameters.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_ORIGIN_STORAGE, true);
        contract.getState().setJS(js.getBytes(), "client script.js", scriptParameters);
        contract.seal();
        Paths.get(originStoragePath + JSApiHelpers.hashId2hex(contract.getOrigin()) + "/" + testFileName).toFile().delete();
        ScriptObjectMirror res = (ScriptObjectMirror)contract.execJS(new JSApiExecOptions(), js.getBytes());
        assertEquals(testString1, res.get("0"));
        assertEquals(testString2, res.get("1"));
        Contract contract2 = contract.createRevision();
        String js2 = "";
        js2 += "print('testOriginStorage_2');";
        js2 += "function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}";
        js2 += "var originStorage = jsApi.getOriginStorage();";
        js2 += "var file2readed = bin2string(originStorage.readAllBytes('"+testFileName+"'));";
        js2 += "originStorage.rewriteExistingFile('"+testFileName+"', jsApi.string2bin('"+testString1+"'));";
        js2 += "var file1readed = bin2string(originStorage.readAllBytes('"+testFileName+"'));";
        js2 += "var result = [file1readed, file2readed]";
        contract2.getState().setJS(js2.getBytes(), "client script.js", scriptParameters);
        contract2.addSignerKey(TestKeys.privateKey(0));
        contract2.seal();
        ScriptObjectMirror res2 = (ScriptObjectMirror)contract2.execJS(new JSApiExecOptions(), js2.getBytes());
        assertEquals(testString1, res2.get("0"));
        assertEquals(testString2, res2.get("1"));
    }

    @Test
    public void testRevisionStorage() throws Exception {
        String revisionStoragePath = JSApiStorage.getRevisionStoragePath();
        String testFileName = "./someFolder/file1.txt";
        String testString1a = "testString1a_" + HashId.createRandom().toBase64String();
        String testString1b = "testString1b_" + HashId.createRandom().toBase64String();
        String testString2a = "testString2a_" + HashId.createRandom().toBase64String();
        String testString2b = "testString2b_" + HashId.createRandom().toBase64String();
        String testString3a = "testString3a_" + HashId.createRandom().toBase64String();
        String testString3b = "testString3b_" + HashId.createRandom().toBase64String();
        System.out.println("testString1a: " + testString1a);
        System.out.println("testString1b: " + testString1b);
        System.out.println("testString2a: " + testString2a);
        System.out.println("testString2b: " + testString2b);
        System.out.println("testString3a: " + testString3a);
        System.out.println("testString3b: " + testString3b);
        Contract contract1 = new Contract(TestKeys.privateKey(0));
        String js1 = "";
        js1 += "print('testRevisionStorage');";
        js1 += "function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}";
        js1 += "var revisionStorage = jsApi.getRevisionStorage();";
        js1 += "revisionStorage.writeNewFile('"+testFileName+"', jsApi.string2bin('"+testString1a+"'));";
        js1 += "var file1Areaded = bin2string(revisionStorage.readAllBytes('"+testFileName+"'));";
        js1 += "revisionStorage.rewriteExistingFile('"+testFileName+"', jsApi.string2bin('"+testString1b+"'));";
        js1 += "var file1Breaded = bin2string(revisionStorage.readAllBytes('"+testFileName+"'));";
        js1 += "var fileParentReaded = null;";
        js1 += "try {";
        js1 += "  fileParentReaded = revisionStorage.readAllBytesFromParent('"+testFileName+"');";
        js1 += "} catch (err) {";
        js1 += "  fileParentReaded = null;";
        js1 += "}";
        js1 += "var result = [file1Areaded, file1Breaded, fileParentReaded]";
        JSApiScriptParameters scriptParameters = new JSApiScriptParameters();
        scriptParameters.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_REVISION_STORAGE, true);
        contract1.getState().setJS(js1.getBytes(), "client script.js", scriptParameters);
        contract1.seal();
        System.out.println("contract1.getId: " + Bytes.toHex(contract1.getId().getDigest()).replaceAll(" ", ""));
        System.out.println("contract1.getParent: " + contract1.getParent());
        Paths.get(revisionStoragePath + JSApiHelpers.hashId2hex(contract1.getId()) + "/" + testFileName).toFile().delete();
        ScriptObjectMirror res = (ScriptObjectMirror)contract1.execJS(new JSApiExecOptions(), js1.getBytes());
        assertEquals(testString1a, res.get("0"));
        assertEquals(testString1b, res.get("1"));
        assertNull(res.get("2"));

        Contract contract2 = contract1.createRevision();
        String js2 = "";
        js2 += "print('testRevisionStorage_2');";
        js2 += "function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}";
        js2 += "var revisionStorage = jsApi.getRevisionStorage();";
        js2 += "revisionStorage.writeNewFile('"+testFileName+"', jsApi.string2bin('"+testString2a+"'));";
        js2 += "var file2Areaded = bin2string(revisionStorage.readAllBytes('"+testFileName+"'));";
        js2 += "revisionStorage.rewriteExistingFile('"+testFileName+"', jsApi.string2bin('"+testString2b+"'));";
        js2 += "var file2Breaded = bin2string(revisionStorage.readAllBytes('"+testFileName+"'));";
        js2 += "var fileParentReaded = revisionStorage.readAllBytesFromParent('"+testFileName+"');";
        js2 += "fileParentReaded = bin2string(fileParentReaded);";
        js2 += "var result = [file2Areaded, file2Breaded, fileParentReaded]";
        contract2.getState().setJS(js2.getBytes(), "client script.js", scriptParameters);
        contract2.seal();
        System.out.println("contract2.getId: " + Bytes.toHex(contract2.getId().getDigest()).replaceAll(" ", ""));
        System.out.println("contract2.getParent: " + Bytes.toHex(contract2.getParent().getDigest()).replaceAll(" ", ""));
        Paths.get(revisionStoragePath + JSApiHelpers.hashId2hex(contract2.getId()) + "/" + testFileName).toFile().delete();
        ScriptObjectMirror res2 = (ScriptObjectMirror)contract2.execJS(new JSApiExecOptions(), js2.getBytes());
        assertEquals(testString2a, res2.get("0"));
        assertEquals(testString2b, res2.get("1"));
        assertEquals(testString1b, res2.get("2"));

        Contract contract3 = contract2.createRevision();
        String js3 = "";
        js3 += "print('testRevisionStorage_3');";
        js3 += "function bin2string(array){var result = '';for(var i = 0; i < array.length; ++i){result+=(String.fromCharCode(array[i]));}return result;}";
        js3 += "var revisionStorage = jsApi.getRevisionStorage();";
        js3 += "revisionStorage.writeNewFile('"+testFileName+"', jsApi.string2bin('"+testString3a+"'));";
        js3 += "var file3Areaded = bin2string(revisionStorage.readAllBytes('"+testFileName+"'));";
        js3 += "revisionStorage.rewriteExistingFile('"+testFileName+"', jsApi.string2bin('"+testString3b+"'));";
        js3 += "var file3Breaded = bin2string(revisionStorage.readAllBytes('"+testFileName+"'));";
        js3 += "var fileParentReaded = revisionStorage.readAllBytesFromParent('"+testFileName+"');";
        js3 += "fileParentReaded = bin2string(fileParentReaded);";
        js3 += "var result = [file3Areaded, file3Breaded, fileParentReaded]";
        contract3.getState().setJS(js3.getBytes(), "client script.js", scriptParameters);
        contract3.seal();
        System.out.println("contract3.getId: " + Bytes.toHex(contract3.getId().getDigest()).replaceAll(" ", ""));
        System.out.println("contract3.getParent: " + Bytes.toHex(contract3.getParent().getDigest()).replaceAll(" ", ""));
        Paths.get(revisionStoragePath + JSApiHelpers.hashId2hex(contract3.getId()) + "/" + testFileName).toFile().delete();
        ScriptObjectMirror res3 = (ScriptObjectMirror)contract3.execJS(new JSApiExecOptions(), js3.getBytes());
        assertEquals(testString3a, res3.get("0"));
        assertEquals(testString3b, res3.get("1"));
        assertEquals(testString2b, res3.get("2"));
    }

    @Test
    public void scriptPermissionsToBinder() throws Exception {
        JSApiScriptParameters params = new JSApiScriptParameters();
        params.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_ORIGIN_STORAGE, true);
        params.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_REVISION_STORAGE, true);
        params.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_STORAGE, true);
        params.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_FOLDERS, true);
        params = JSApiScriptParameters.fromBinder(params.toBinder());
        assertTrue(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_ORIGIN_STORAGE));
        assertTrue(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_REVISION_STORAGE));
        assertTrue(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_STORAGE));
        assertTrue(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_FOLDERS));

        params.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_ORIGIN_STORAGE, false);
        params.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_REVISION_STORAGE, false);
        params.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_STORAGE, false);
        params.setPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_FOLDERS, false);
        params = JSApiScriptParameters.fromBinder(params.toBinder());
        assertFalse(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_ORIGIN_STORAGE));
        assertFalse(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_REVISION_STORAGE));
        assertFalse(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_STORAGE));
        assertFalse(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_FOLDERS));
    }

    @Test
    public void scriptPermissionsDefaultStates() throws Exception {
        JSApiScriptParameters params = new JSApiScriptParameters();
        params = JSApiScriptParameters.fromBinder(params.toBinder());
        assertFalse(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_ORIGIN_STORAGE));
        assertFalse(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_REVISION_STORAGE));
        assertFalse(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_STORAGE));
        assertTrue(params.checkPermission(JSApiScriptParameters.ScriptPermissions.PERM_SHARED_FOLDERS));
    }

    @Test
    public void testUrlParser() throws Exception {
        JSApiUrlParser urlParser = new JSApiUrlParser();

        urlParser.addUrlMask("universa.com");
        urlParser.addUrlMask("t2.universa.com:80");
        urlParser.addUrlMask("t3.universa.com:3333");
        urlParser.addUrlMask("universa.io");
        urlParser.addUrlMask("test1.universa.io:80");
        urlParser.addUrlMask("test2.universa.io:443");
        urlParser.addUrlMask("test3.universa.io:8080");
        urlParser.addUrlMask("test4.universa.io:*");
        urlParser.addUrlMask("*.universa.io");
        urlParser.addUrlMask("*.universa.io:4444");

        urlParser.addIpMask("192.168.33.44");
        urlParser.addIpMask("192.168.44.*:8080");

        assertTrue(urlParser.isUrlAllowed("https://www.universa.io/imgres?imgurl=http%3A%2F%2Fkaifolog.ru%2Fuploads%2Fposts%2F2014-02%2F1392187237_005.jpg&imgrefurl=http%3A%2F%2Fkaifolog.ru%2Fpozitiv%2F5234-koteyki-55-foto.html&docid=5_IgRUU_v1M82M&tbnid=fN4J5V9ZY-tIiM%3A&vet=10ahUKEwjYn63jx43dAhVkkosKHW4TAcsQMwiOASgDMAM..i&w=640&h=640&bih=978&biw=1920&q=%D0%BA%D0%BE%D1%82%D0%B5%D0%B9%D0%BA%D0%B8&ved=0ahUKEwjYn63jx43dAhVkkosKHW4TAcsQMwiOASgDMAM&iact=mrc&uact=8"));
        assertTrue(urlParser.isUrlAllowed("universa.io."));
        assertTrue(urlParser.isUrlAllowed("http://universa.io"));
        assertTrue(urlParser.isUrlAllowed("https://universa.io"));
        assertFalse(urlParser.isUrlAllowed("universa.io.:8080"));
        assertFalse(urlParser.isUrlAllowed("test2.universa.io.:8080"));
        assertTrue(urlParser.isUrlAllowed("test3.universa.io.:8080"));
        assertTrue(urlParser.isUrlAllowed("test4.universa.io.:8080"));
        assertTrue(urlParser.isUrlAllowed("universa.io.:80"));
        assertTrue(urlParser.isUrlAllowed("universa.io.:443"));
        assertTrue(urlParser.isUrlAllowed("test55.universa.io"));
        assertFalse(urlParser.isUrlAllowed("test55.universa.io:3333"));
        assertTrue(urlParser.isUrlAllowed("http://test55.universa.io"));
        assertTrue(urlParser.isUrlAllowed("p1.test55.universa.io"));
        assertFalse(urlParser.isUrlAllowed("p1.test55.universa.io:3333"));
        assertTrue(urlParser.isUrlAllowed("p1.test55.universa.io:4444"));
        assertTrue(urlParser.isUrlAllowed("universa.com"));
        assertFalse(urlParser.isUrlAllowed("t1.universa.com"));
        assertFalse(urlParser.isUrlAllowed("t2.universa.com"));
        assertTrue(urlParser.isUrlAllowed("http://t2.universa.com"));
        assertFalse(urlParser.isUrlAllowed("http://t3.universa.com"));
        assertFalse(urlParser.isUrlAllowed("https://t3.universa.com"));
        assertTrue(urlParser.isUrlAllowed("http://t3.universa.com:3333"));
        assertTrue(urlParser.isUrlAllowed("https://t3.universa.com:3333"));

        assertTrue(urlParser.isUrlAllowed("192.168.33.44"));
        assertFalse(urlParser.isUrlAllowed("192.168.33.45"));
        assertFalse(urlParser.isUrlAllowed("192.168.32.44"));
        assertFalse(urlParser.isUrlAllowed("192.168.32.44:3333"));
        assertTrue(urlParser.isUrlAllowed("http://192.168.33.44"));
        assertFalse(urlParser.isUrlAllowed("192.168.44.55"));
        assertTrue(urlParser.isUrlAllowed("192.168.44.55:8080"));
    }

    @Test
    public void testHttpClient() throws Exception {
        JSApiHttpClient client = new JSApiHttpClient();
        List res = client.sendGetRequest("http://httpbin.org/get?param=333", "json");
        System.out.println("resp code: " + res.get(0));
        System.out.println("resp body: " + res.get(1));
        res = client.sendPostRequest("http://httpbin.org/post", "json", Binder.of("postparam", 44), "form");
        System.out.println("resp code: " + res.get(0));
        System.out.println("resp body: " + res.get(1));
        res = client.sendPostRequest("http://httpbin.org/post", "json", Binder.of("postparam", 44), "json");
        System.out.println("resp code: " + res.get(0));
        System.out.println("resp body: " + res.get(1));
    }

}
