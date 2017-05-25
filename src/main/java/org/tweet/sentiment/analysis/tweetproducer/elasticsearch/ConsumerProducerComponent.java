package org.tweet.sentiment.analysis.tweetproducer.elasticsearch;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Search;
import io.searchbox.indices.IndicesExists;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.util.logging.Logger;

public class ConsumerProducerComponent extends Thread {

    private static final Logger logger = Logger.getLogger(ConsumerProducerComponent.class.getName());

    public static final String ES_INDEX_NAME = "ES_INDEX_NAME"; // twitter
    public static final String ES_TYPE_NAME  = "ES_TYPE_NAME"; // tweet

    public static final String ES_HOST     = "ES_HOST";
    public static final String ES_PORT     = "ES_PORT";
    public static final String ES_USERNAME = "ES_USERNAME";
    public static final String ES_PASSWORD = "ES_PASSWORD";

    public static final String SQS_QUEUE_NAME = "SQS_QUEUE_NAME";

    private AmazonSQS  sqs;
    private JestClient elasticsearchClient;

    public ConsumerProducerComponent()
            throws IOException {
        this.init();
    }

    private void init()
            throws IOException {
        AWSCredentials credentials;
        try {
            credentials = new EnvironmentVariableCredentialsProvider().getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the environment. " +
                            "Please make sure that your credentials are located in the environment variables " +
                            "AWS_ACCESS_KEY_ID resp. AWS_SECRET_ACCESS_KEY",
                    e);
        }

        AmazonSQSClientBuilder clientBuilder = AmazonSQSClientBuilder.standard().withCredentials(new AWSStaticCredentialsProvider(credentials));
        clientBuilder.setRegion(Regions.US_WEST_2.getName());
        sqs = clientBuilder.build();

        String esHost = System.getenv(ES_HOST);
        int esPort = Integer.parseInt(System.getenv(ES_PORT));
        String esUsername = System.getenv(ES_USERNAME);
        String esPassword = System.getenv(ES_PASSWORD);
        String esIndex = System.getenv(ES_INDEX_NAME);
        String esType = System.getenv(ES_TYPE_NAME);

        logger.info("Trying to connect to elasticsearch on " + esHost + ":" + esPort + " using " + esUsername + ":" + esPassword);
        logger.info("Elasticsearch index is: " + esIndex + ", type: " + esType);

        // Construct a new Jest client according to configuration via factory
        JestClientFactory factory = new JestClientFactory();
        factory.setHttpClientConfig(new HttpClientConfig
                .Builder(esHost + ":" + esPort)
                .defaultCredentials(esUsername, esPassword)
                .multiThreaded(true)
                //Per default this implementation will create no more than 2 concurrent connections per given route
                .defaultMaxTotalConnectionPerRoute(1)
                // and no more 20 connections in total
                .maxTotalConnection(20)
                .build());
        this.elasticsearchClient = factory.getObject();

        // check whether the index and the type already exist
        // create otherwise
        logger.info("Checking whether ES index " + esIndex + " exists...");
        boolean indexExists = this.elasticsearchClient.execute(new IndicesExists.Builder(esIndex).build()).isSucceeded();
        logger.info("Index exists: " + indexExists);

        if (! indexExists) {
            throw new IllegalStateException("Index " + esIndex + " does not exist. Aborting");
        }
    }

    @Override
    public void run() {
        // Get twitter message from queue
        try {
            String esIndex = System.getenv(ES_INDEX_NAME);
            String esType = System.getenv(ES_TYPE_NAME);

            String query = "{\n" +
                    "    \"query\": {\n" +
                    "        \"match_all\": {}\n" +
                    "    }\n" +
                    "}";

            Search search = new Search.Builder(query)
                    // multiple index or types can be added.
                    .addIndex(esIndex)
                    .addType(esType)
                    .build();

            // Receive messages
            String fetchedTweetsQueueName = System.getenv(SQS_QUEUE_NAME);

            logger.info("Getting url for fetched tweets queue");
            String queueUrl = sqs.getQueueUrl(fetchedTweetsQueueName).getQueueUrl();
            logger.info("Url is: " + queueUrl);

            while (true) {
                JsonObject result = this.elasticsearchClient.execute(search).getJsonObject();

                JsonArray hits = result.get("hits").getAsJsonObject().get("hits").getAsJsonArray();

                logger.info("Fetched " + hits.size() + " tweets from Elasticsearch");

                JSONParser parser = new JSONParser();
                for (JsonElement hit : hits) {
                    JsonElement source = hit.getAsJsonObject().get("_source");

                    if (null == source) {
                        continue;
                    }

                    JsonObject entry = source.getAsJsonObject();

                    Long id;
                    String tweet;
                    String term;

                    try {
                        id = entry.get("id").getAsLong();
                        tweet = entry.get("tweet").toString();
                        term = entry.get("term").getAsString();
                    } catch (Exception e) {
                        logger.warning("Could not parse tweet. Skipping...");
                        continue;
                    }

                    // we need to parse the tweet again to avoid
                    // having escaped quotes in the resulting JSON string
                    JSONObject tweetObj;
                    try {
                         tweetObj = (JSONObject) parser.parse(tweet);
                    } catch (ParseException e) {
                        logger.warning("Failed to parse tweet: " + tweet);
                        continue;
                    }

                    JSONObject wrapperObj = new JSONObject();
                    wrapperObj.put("id", id*10);
                    wrapperObj.put("timestamp", System.currentTimeMillis());
                    wrapperObj.put("tweet", tweetObj);
                    wrapperObj.put("term", term);

                    String msgBody = wrapperObj.toJSONString();

                    try {
                        this.sqs.sendMessage(queueUrl, msgBody);
                    } catch (Exception e) {
                        logger.warning("Could not send tweet to SQS: " + e.getMessage());
                    }
                }

                Thread.sleep(100);

            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
        }
    }
}
