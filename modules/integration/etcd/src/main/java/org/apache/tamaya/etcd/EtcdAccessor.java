/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tamaya.etcd;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonReaderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Accessor for reading/writing an etcd endpoint.
 */
public class EtcdAccessor {

    private static final Logger LOG = Logger.getLogger(EtcdAccessor.class.getName());

    /** Property that make Johnzon accept commentc. */
    public static final String JOHNZON_SUPPORTS_COMMENTS_PROP = "org.apache.johnzon.supports-comments";
    /** The JSON reader factory used. */
    private JsonReaderFactory readerFactory = initReaderFactory();

    /** Initializes the factory to be used for creating readers. */
    private JsonReaderFactory initReaderFactory() {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put(JOHNZON_SUPPORTS_COMMENTS_PROP, true);
        return Json.createReaderFactory(config);
    }

    /** The base server url. */
    private String serverURL;
    /** The http client. */
    private CloseableHttpClient httpclient = HttpClients.createDefault();

    /**
     * Creates a new instance, trying to read the basic server endpoint from {@code -Detcd.url}, using default
     * value of {@code http://127.0.0.1:4001}.
     * @throws MalformedURLException
     */
    public EtcdAccessor() throws MalformedURLException {
        this(System.getProperty("etcd.url", "http://127.0.0.1:4001"));
    }

    /**
     * Creates a new instance with the basic access url.
     * @param server server url, e.g. {@code http://127.0.0.1:4001}.
     * @throws MalformedURLException
     */
    public EtcdAccessor(String server) throws MalformedURLException {
        if(server.endsWith("/")){
            serverURL = server.substring(0, server.length()-1);
        } else{
            serverURL = server;
        }

    }

    /**
     * Get the etcd server version.
     * @return the etcd server version, never null.
     */
    public String getVersion(){
        CloseableHttpResponse response = null;
        String version = "<ERROR>";
        try {
            CloseableHttpClient httpclient = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet(serverURL + "/version");
            response = httpclient.execute(httpGet);
            HttpEntity entity = null;
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                entity = response.getEntity();
                // and ensure it is fully consumed
                version = EntityUtils.toString(entity);
                EntityUtils.consume(entity);
            }
            return version;
        } catch(Exception e){
            // TODO log error
        } finally {
            if(response!=null){
                try {
                    response.close();
                } catch (IOException e) {
                    // TODO log error
                }
            }
        }
        return version;
    }

    /**
     * Ask etcd for s aingle key, value pair. Hereby the response returned from etcd:
     * <pre>
     * {
         "action": "get",
         "node": {
         "createdIndex": 2,
         "key": "/message",
         "modifiedIndex": 2,
         "value": "Hello world"
         }
     * }
     * </pre>
     * is mapped to:
     * <pre>
     *     key=value
     *     _key.source=[etcd]http://127.0.0.1:4001
     *     _key.createdIndex=12
     *     _key.modifiedIndex=34
     *     _key.ttl=300
     *     _key.expiration=...
     * </pre>
     * @param key the requested key
     * @return the mapped result, including meta-entries.
     */
    public Map<String,String> get(String key){
        CloseableHttpResponse response = null;
        Map<String,String> result = new HashMap<>();
        result.put("_" + key +".source", "[etcd]"+serverURL);
        try {
            HttpGet httpGet = new HttpGet(serverURL + "/v2/keys/"+key);
            response = httpclient.execute(httpGet);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                HttpEntity entity = response.getEntity();
                JsonReader reader = readerFactory.createReader(new StringReader(EntityUtils.toString(entity)));
                JsonObject o = reader.readObject();
                JsonObject node = o.getJsonObject("node");
                if(node.containsKey("createdIndex")) {
                    result.put("_" + key +".createdIndex", String.valueOf(node.getInt("createdIndex")));
                }
                if(node.containsKey("modifiedIndex")) {
                    result.put("_" + key +".modifiedIndex", String.valueOf(node.getInt("modifiedIndex")));
                }
                if(node.containsKey("expiration")) {
                    result.put("_" + key +".expiration", String.valueOf(node.getInt("expiration")));
                }
                if(node.containsKey("_" + key +".ttl")) {
                    result.put("_" + key +".ttl", String.valueOf(node.getInt("ttl")));
                }
                result.put(key, o.getString("value"));
                EntityUtils.consume(entity);
            }
        } catch(Exception e){
            // TODO log error
            result.put("_" + key +".error", e.toString());
        } finally {
            if(response!=null){
                try {
                    response.close();
                } catch (IOException e) {
                    // TODO log error
                }
            }
        }
        return result;
    }

    /**
     * Creates/updates an entry in etcd. The response as follows:
     * <pre>
     *     {
     "action": "set",
     "node": {
     "createdIndex": 3,
     "key": "/message",
     "modifiedIndex": 3,
     "value": "Hello etcd"
     },
     "prevNode": {
     "createdIndex": 2,
     "key": "/message",
     "value": "Hello world",
     "modifiedIndex": 2
     }
     }
     * </pre>
     * is mapped to:
     * <pre>
     *     key=value
     *     _key.source=[etcd]http://127.0.0.1:4001
     *     _key.createdIndex=12
     *     _key.modifiedIndex=34
     *     _key.ttl=300
     *     _key.expiry=...
     *      // optional
     *     _key.prevNode.createdIndex=12
     *     _key.prevNode.modifiedIndex=34
     *     _key.prevNode.ttl=300
     *     _key.prevNode.expiration=...
     * </pre>
     * @param key the property key, not null
     * @param value the value to be set
     * @param ttlSeconds the ttl in seconds (optional)
     * @return the result map as described above.
     */
    public Map<String,String> set(String key, String value, Integer ttlSeconds){
        CloseableHttpResponse response = null;
        Map<String,String> result = new HashMap<>();
        result.put("_" + key +".source", "[etcd]"+serverURL);
        try{
            HttpPut put = new HttpPut(serverURL + "/v2/keys/"+key);
            List<NameValuePair> nvps = new ArrayList<>();
            nvps.add(new BasicNameValuePair("value", value));
            if(ttlSeconds!=null){
                result.put("ttl", ttlSeconds.toString());
                nvps.add(new BasicNameValuePair("ttl", ttlSeconds.toString()));
            }
            put.setEntity(new UrlEncodedFormEntity(nvps));
            response = httpclient.execute(put);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                HttpEntity entity = response.getEntity();
                JsonReader reader = readerFactory.createReader(new StringReader(EntityUtils.toString(entity)));
                JsonObject o = reader.readObject();
                JsonObject node = o.getJsonObject("node");
                if(node.containsKey("createdIndex")) {
                    result.put("_" + key +".createdIndex", String.valueOf(node.getInt("createdIndex")));
                }
                if(node.containsKey("modifiedIndex")) {
                    result.put("_" + key +".modifiedIndex", String.valueOf(node.getInt("modifiedIndex")));
                }
                if(node.containsKey("expiration")) {
                    result.put("_" + key +".expiration", String.valueOf(node.getInt("expiration")));
                }
                if(node.containsKey("_" + key +".ttl")) {
                    result.put("_" + key +".ttl", String.valueOf(node.getInt("ttl")));
                }
                result.put("value", node.getString("value"));
                JsonObject prevNode = o.getJsonObject("prevNode");
                if(prevNode!=null) {
                    if (prevNode.containsKey("createdIndex")) {
                        result.put("_" + key +".prevNode.createdIndex", String.valueOf(prevNode.getInt("createdIndex")));
                    }
                    if (prevNode.containsKey("modifiedIndex")) {
                        result.put("_" + key +".prevNode.modifiedIndex", String.valueOf(prevNode.getInt("modifiedIndex")));
                    }
                    if(prevNode.containsKey("expiration")) {
                        result.put("_" + key +".prevNode.expiration", String.valueOf(prevNode.getInt("expiration")));
                    }
                    if(prevNode.containsKey("ttl")) {
                        result.put("_" + key +".prevNode.ttl", String.valueOf(prevNode.getInt("ttl")));
                    }
                    result.put("_" + key +".prevNode.value", prevNode.getString("value"));
                }
                EntityUtils.consume(entity);
            }
        } catch(Exception e){
            // TODO log error
            result.put("_" + key +".error", e.toString());
        } finally {
            if(response!=null){
                try {
                    response.close();
                } catch (IOException e) {
                    // TODO log error
                }
            }
        }
        return result;
    }


    /**
     * Deletes a given key. The response is as follows:
     * <pre>
     *     _key.source=[etcd]http://127.0.0.1:4001
     *     _key.createdIndex=12
     *     _key.modifiedIndex=34
     *     _key.ttl=300
     *     _key.expiry=...
     *      // optional
     *     _key.prevNode.createdIndex=12
     *     _key.prevNode.modifiedIndex=34
     *     _key.prevNode.ttl=300
     *     _key.prevNode.expiration=...
     *     _key.prevNode.value=...
     * </pre>
     * @param key the key to be deleted.
     * @return the response mpas as described above.
     */
    public Map<String,String> delete(String key){
        CloseableHttpResponse response = null;
        Map<String,String> result = new HashMap<>();
        result.put("key", key);
        result.put("_" + key +".source", "[etcd]"+serverURL);
        try{
            HttpDelete delete = new HttpDelete(serverURL + "/v2/keys/"+key);
            List<NameValuePair> nvps = new ArrayList<>();
            // delete.setEntity(new UrlEncodedFormEntity(nvps));
            response = httpclient.execute(delete);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                HttpEntity entity = response.getEntity();
                JsonReader reader = readerFactory.createReader(new StringReader(EntityUtils.toString(entity)));
                JsonObject o = reader.readObject();
                JsonObject node = o.getJsonObject("node");
                if(node.containsKey("createdIndex")) {
                    result.put("_" + key +".createdIndex", String.valueOf(node.getInt("createdIndex")));
                }
                if(node.containsKey("modifiedIndex")) {
                    result.put("_" + key +".modifiedIndex", String.valueOf(node.getInt("modifiedIndex")));
                }
                if(node.containsKey("expiration")) {
                    result.put("_" + key +".expiration", String.valueOf(node.getInt("expiration")));
                }
                if(node.containsKey("ttl")) {
                    result.put("_" + key +".ttl", String.valueOf(node.getInt("ttl")));
                }
                JsonObject prevNode = o.getJsonObject("prevNode");
                if(prevNode!=null) {
                    if (prevNode.containsKey("createdIndex")) {
                        result.put("_" + key +".prevNode.createdIndex", String.valueOf(prevNode.getInt("createdIndex")));
                    }
                    if (prevNode.containsKey("modifiedIndex")) {
                        result.put("_" + key +".prevNode.modifiedIndex", String.valueOf(prevNode.getInt("modifiedIndex")));
                    }
                    if(prevNode.containsKey("expiration")) {
                        result.put("_" + key +".prevNode.expiration", String.valueOf(prevNode.getInt("expiration")));
                    }
                    if(prevNode.containsKey("ttl")) {
                        result.put("_" + key +".prevNode.ttl", String.valueOf(prevNode.getInt("ttl")));
                    }
                    result.put("_" + key +".prevNode.value", prevNode.getString("value"));
                }
                EntityUtils.consume(entity);
            }
        } catch(Exception e){
            // TODO log error
            result.put("_" + key +".error", e.toString());
        } finally {
            if(response!=null){
                try {
                    response.close();
                } catch (IOException e) {
                    // TODO log error
                }
            }
        }
        return result;
    }

    /**
     * Get all properties for the given directory key recursively.
     * @see #getProperties(String, boolean)
     * @param directory the directory entry
     * @return the properties and its metadata
     */
    public Map<String,String> getProperties(String directory){
        return getProperties(directory, true);
    }

    /**
     * Access all properties.
     * The response of:
     * <pre>
    {
    "action": "get",
    "node": {
        "key": "/",
        "dir": true,
        "nodes": [
            {
                "key": "/foo_dir",
                "dir": true,
                "modifiedIndex": 2,
                "createdIndex": 2
            },
            {
                "key": "/foo",
                "value": "two",
                "modifiedIndex": 1,
                "createdIndex": 1
            }
        ]
    }
}
     </pre>
     is mapped to a regular Tamaya properties map as follows:
     <pre>
     *    key1=myvalue
     *     _key1.source=[etcd]http://127.0.0.1:4001
     *     _key1.createdIndex=12
     *     _key1.modifiedIndex=34
     *     _key1.ttl=300
     *     _key1.expiration=...
     *
     *      key2=myvaluexxx
     *     _key2.source=[etcd]http://127.0.0.1:4001
     *     _key2.createdIndex=12
     *
     *      key3=val3
     *     _key3.source=[etcd]http://127.0.0.1:4001
     *     _key3.createdIndex=12
     *     _key3.modifiedIndex=2
     * </pre>
     */
    public Map<String,String> getProperties(String directory, boolean recursive){
        CloseableHttpResponse response = null;
        Map<String,String> result = new HashMap<>();
        result.put("_" + directory +".source", "[etcd]"+serverURL);
        try{
            HttpGet get = new HttpGet(serverURL + "/v2/keys/"+directory+"?recursive="+recursive);
            response = httpclient.execute(get);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                result.put("_" + directory +".source", "[etcd]"+serverURL);
                HttpEntity entity = response.getEntity();
                JsonReader reader = readerFactory.createReader(new StringReader(EntityUtils.toString(entity)));
                JsonObject o = reader.readObject();
                JsonObject node = o.getJsonObject("node");
                if(node!=null){
                    addNodes(result, node);
                }
                EntityUtils.consume(entity);
            }
        } catch(Exception e){
            // TODO log error
            result.put("_" + directory +".error", e.toString());
        } finally {
            if(response!=null){
                try {
                    response.close();
                } catch (IOException e) {
                    // TODO log error
                }
            }
        }
        return result;
    }

    /**
     * Recursively read out all key/values from this etcd JSON array.
     * @param result
     * @param node
     */
    private void addNodes(Map<String, String> result, JsonObject node) {
        if(node.getBoolean("dir", false)) {
            String key = node.getString("key").substring(1);
            result.put(key, node.getString("value"));
            if (node.containsKey("createdIndex")) {
                result.put("_" + key + ".createdIndex", String.valueOf(node.getInt("createdIndex")));
            }
            if (node.containsKey("modifiedIndex")) {
                result.put("_" + key + ".modifiedIndex", String.valueOf(node.getInt("modifiedIndex")));
            }
            if (node.containsKey("expiration")) {
                result.put("_" + key + ".expiration", String.valueOf(node.getInt("expiration")));
            }
            if (node.containsKey("ttl")) {
                result.put("_" + key + ".ttl", String.valueOf(node.getInt("ttl")));
            }
            result.put("_" + key +".source", "[etcd]"+serverURL);
        } else {
            JsonArray nodes = node.getJsonArray("node");
            if (nodes != null) {
                for (int i = 0; i < nodes.size(); i++) {
                    addNodes(result, nodes.getJsonObject(i));
                }
            }
        }
    }


}
