/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.origin.mongodb;

import com.mongodb.CursorType;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientException;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoQueryException;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.ErrorCode;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.lib.util.JsonUtil;
import com.streamsets.pipeline.lib.util.ThreadUtil;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MongoDBSource extends BaseSource {
  private static final Logger LOG = LoggerFactory.getLogger(MongoDBSource.class);
  public static final String _ID = "_id";

  private static final DateFormat dateFormat = new SimpleDateFormat("YYYY-MM-DD HH:mm:ss");

  private final String mongoConnectionString;
  private final String mongoDatabaseName;
  private final String mongoCollectionName;
  private final boolean isCapped;
  private final String offsetField;
  private final String initialOffset;
  private final int batchSize;
  private final long maxBatchWaitTime;
  private final AuthenticationType authenticationType;
  private final String username;
  private final String password;
  private final ReadPreference readPreference;

  private ObjectId initialObjectId;

  private MongoClient mongoClient;
  private MongoDatabase mongoDatabase;
  private MongoCollection<Document> mongoCollection;
  private MongoCursor<Document> cursor;

  public MongoDBSource(
      String mongoConnectionString,
      String mongoDatabaseName,
      String mongoCollectionName,
      boolean isCapped,
      String offsetField,
      String initialOffset,
      int batchSize,
      long maxBatchWaitTime,
      AuthenticationType authenticationType,
      String username,
      String password,
      ReadPreference readPreference
  ) {
    this.mongoConnectionString = mongoConnectionString;
    this.mongoDatabaseName = mongoDatabaseName;
    this.mongoCollectionName = mongoCollectionName;
    this.isCapped = isCapped;
    this.offsetField = offsetField;
    this.initialOffset = initialOffset;
    this.batchSize = batchSize;
    this.maxBatchWaitTime = maxBatchWaitTime * 1000; // Convert from seconds to milliseconds
    this.authenticationType = authenticationType;
    this.username = username;
    this.password = password;
    this.readPreference = readPreference;
  }

  @Override
  protected List<ConfigIssue> validateConfigs() throws StageException {
    List<ConfigIssue> issues = super.validateConfigs();

    try {
      initialObjectId = new ObjectId(dateFormat.parse(initialOffset));
    } catch (ParseException e) {
      issues.add(getContext()
              .createConfigIssue(
                  Groups.MONGODB.name(),
                  "initialOffset",
                  Errors.MONGODB_05,
                  initialOffset
              )
      );
    }

    if (createMongoClient(issues)) {
      if (checkMongoDatabase(issues)) {
        if (checkMongoCollection(issues)) {
          checkCursor(issues);
        }
      }
      mongoClient.close();
      mongoClient = null;
    }

    return issues;
  }

  @Override
  public void destroy() {
    if (null != cursor) {
      cursor.close();
    }
    mongoClient.close();
    mongoClient = null;

    super.destroy();
  }

  @Override
  public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
    String nextSourceOffset = lastSourceOffset;
    int numRecords = 0;

    prepareCursor(maxBatchSize, offsetField, lastSourceOffset);
    long batchWaitTime = System.currentTimeMillis() + maxBatchWaitTime;

    try {
      while (numRecords < Math.min(batchSize, maxBatchSize) && System.currentTimeMillis() < batchWaitTime) {
        LOG.trace("Trying to get next doc from cursor");
        Document doc = cursor.tryNext();
        if (null == doc) {
          LOG.trace("Doc was null");
          if (!isCapped) {
            LOG.trace("Collection is not capped.");
            // If this is not a capped collection, then this means we've reached the end of the data.
            // and should get a new cursor.
            LOG.trace("Closing cursor.");
            cursor.close();
            cursor = null;
            // Wait the remaining time we have for this batch before trying again.
            long waitTime = Math.max(0, batchWaitTime - System.currentTimeMillis());
            LOG.trace("Sleeping for: {}", waitTime);
            ThreadUtil.sleep(waitTime);
            return nextSourceOffset;
          }
          continue;
        }

        Set<Map.Entry<String, Object>> entrySet = doc.entrySet();
        Map<String, Field> fields = new HashMap<>(entrySet.size());

        try {
          for (Map.Entry<String, Object> entry : entrySet) {
            Field value;
            if (entry.getValue() instanceof ObjectId) {
              String objectId = entry.getValue().toString();
              value = JsonUtil.jsonToField(objectId);
            } else {
              value = JsonUtil.jsonToField(entry.getValue());
            }
            fields.put(entry.getKey(), value);
          }
        } catch (IOException e) {
          handleError(Errors.MONGODB_10, e.getMessage());
          continue;
        }

        if (!doc.containsKey(_ID)) {
          handleError(Errors.MONGODB_11, offsetField, doc.toString());
          continue;
        }
        nextSourceOffset = doc.getObjectId(offsetField).toHexString();

        final String recordContext = mongoConnectionString + "::" +
            mongoDatabaseName + "::" + mongoCollectionName + "::" +
            nextSourceOffset;

        Record record = getContext().createRecord(recordContext);
        record.set(Field.create(fields));
        batchMaker.addRecord(record);
        ++numRecords;
      }
    } catch (MongoClientException e) {
      LOG.error("Error reading cursor: {}", e);
    }
    return nextSourceOffset;
  }

  private void prepareCursor(int maxBatchSize, String offsetField, String lastSourceOffset) {
    createMongoClient();

    ObjectId offset;
    if (null == cursor) {
      if (null == lastSourceOffset) {
        offset = initialObjectId;
      } else {
        offset = new ObjectId(lastSourceOffset);
      }
      LOG.debug("Getting new cursor with params: {} {} {}", maxBatchSize, offsetField, lastSourceOffset);
      if (isCapped) {
        cursor = mongoCollection
            .find()
            .filter(Filters.gt(offsetField, offset))
            .cursorType(CursorType.TailableAwait)
            .batchSize(maxBatchSize)
            .iterator();
      } else {
        cursor = mongoCollection
            .find()
            .filter(Filters.gt(offsetField, offset))
            .sort(Sorts.ascending(offsetField))
            .cursorType(CursorType.NonTailable)
            .batchSize(maxBatchSize)
            .iterator();
      }
    }
  }

  private List<MongoCredential> createCredentials() {
    List<MongoCredential> credentials = new ArrayList<>(1);
    MongoCredential credential = null;
    switch (authenticationType) {
      case USER_PASS:
        credential = MongoCredential.createCredential(username, mongoDatabaseName, password.toCharArray());
        break;
      case X509:
        credential = MongoCredential.createMongoX509Credential(username);
        break;
      case NONE:
      default:
        break;
    }

    if (null != credential) {
      credentials.add(credential);
    }
    return credentials;
  }

  private void handleError(ErrorCode errorCode, Object... params) throws StageException {
    Source.Context context = getContext();
    switch (context.getOnErrorRecord()) {
      case DISCARD:
        break;
      case TO_ERROR:
        context.reportError(errorCode, params);
        break;
      case STOP_PIPELINE:
        throw new StageException(errorCode, params);
      default:
        throw new IllegalStateException(Utils.format("It should never happen. OnError '{}'",
            context.getOnErrorRecord()));
    }
  }

  private void createMongoClient() {
    List<ConfigIssue> issues = new ArrayList<>();
    if (createMongoClient(issues)) {
      if (checkMongoDatabase(issues)) {
        checkMongoCollection(issues);
      }
    }
  }

  private boolean createMongoClient(List<ConfigIssue> issues) {
    boolean isOk = true;
    if (null == mongoClient) {
      List<ServerAddress> servers = new ArrayList<>();
      isOk = parseServerList(mongoConnectionString, servers, issues);
      List<MongoCredential> credentials = createCredentials();
      MongoClientOptions options = MongoClientOptions.builder().build();

      if (AuthenticationType.X509 == authenticationType) {
        options = MongoClientOptions.builder()
            .socketFactory(SSLSocketFactory.getDefault())
            .build();
      }

      if (isOk) {
        try {
          mongoClient = new MongoClient(servers, credentials, options);
        } catch (MongoException e) {
          issues.add(getContext().createConfigIssue(
              Groups.MONGODB.name(),
              "mongoConnectionString",
              Errors.MONGODB_01,
              e.getMessage()
          ));
          isOk = false;
        }
      }
    }
    return isOk;
  }

  private boolean checkMongoDatabase(List<ConfigIssue> issues) {
    boolean isOk = true;
    try {
      mongoDatabase = mongoClient.getDatabase(mongoDatabaseName).withReadPreference(readPreference);
    } catch (MongoClientException e) {
      issues.add(getContext().createConfigIssue(
          Groups.MONGODB.name(),
          "database",
          Errors.MONGODB_02,
          mongoDatabaseName,
          e.getMessage()
      ));
      isOk = false;
    }
    return isOk;
  }

  private boolean checkMongoCollection(List<ConfigIssue> issues) {
    boolean isOk = true;
    try {
      mongoCollection = mongoDatabase.getCollection(mongoCollectionName).withReadPreference(readPreference);
    } catch (MongoClientException e) {
      issues.add(getContext().createConfigIssue(
          Groups.MONGODB.name(),
          "collection",
          Errors.MONGODB_03,
          mongoCollectionName,
          e.getMessage()
      ));
      isOk = false;
    }
    return isOk;
  }

  private void checkCursor(List<ConfigIssue> issues) {
    if (isCapped) {
      try {
        mongoCollection.find().cursorType(CursorType.TailableAwait).batchSize(1).limit(1).iterator().close();
      } catch (MongoQueryException e) {
        issues.add(getContext().createConfigIssue(
            Groups.MONGODB.name(),
            "collection",
            Errors.MONGODB_04,
            mongoDatabaseName,
            e.getMessage()
        ));
      }
    } else {
      try {
        mongoCollection.find().cursorType(CursorType.NonTailable).batchSize(1).limit(1).iterator().close();
      } catch (MongoQueryException e) {
        issues.add(getContext().createConfigIssue(
            Groups.MONGODB.name(),
            "collection",
            Errors.MONGODB_06,
            mongoDatabaseName,
            e.getMessage()
        ));
      }
    }
  }

  private boolean parseServerList(String mongoConnectionString, List<ServerAddress> servers, List<ConfigIssue> issues) {
    boolean isOk = true;
    MongoClientURI mongoURI = new MongoClientURI(mongoConnectionString);
    List<String> hosts = mongoURI.getHosts();

    // Validate each host in the connection string is valid. MongoClient will not tell us
    // if something is wrong when we try to open it.
    for (String host : hosts) {
      String[] hostport = host.split(":");
      if (hostport.length != 2) {
        issues.add(getContext().createConfigIssue(
            Groups.MONGODB.name(),
            "mongoConnectionString",
            Errors.MONGODB_07,
            host
        ));
        isOk = false;
      } else {
        try {
          InetAddress.getByName(hostport[0]);
          servers.add(new ServerAddress(hostport[0], Integer.parseInt(hostport[1])));
        } catch (UnknownHostException e) {
          issues.add(getContext().createConfigIssue(
              Groups.MONGODB.name(),
              "mongoConnectionString",
              Errors.MONGODB_09,
              hostport[0]
          ));
          isOk = false;
        } catch (NumberFormatException e) {
          issues.add(getContext().createConfigIssue(
              Groups.MONGODB.name(),
              "mongoConnectionString",
              Errors.MONGODB_08,
              hostport[1]
          ));
          isOk = false;
        }
      }
    }
    return isOk;
  }
}