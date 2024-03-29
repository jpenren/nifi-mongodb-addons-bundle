/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nifi.processors.mongodb;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.PrimaryNodeOnly;
import org.apache.nifi.annotation.behavior.Stateful;
import org.apache.nifi.annotation.behavior.TriggerSerially;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.components.state.Scope;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.components.state.StateMap;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractSessionFactoryProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessSessionFactory;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.JsonValidator;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.StopWatch;
import org.apache.nifi.util.StringUtils;
import org.bson.Document;
import org.bson.types.ObjectId;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

@TriggerSerially
@PrimaryNodeOnly
@Tags({"mongo", "get", "fetch", "query"})
@InputRequirement(Requirement.INPUT_FORBIDDEN)
@CapabilityDescription("Creates FlowFiles from documents in MongoDB. This implementation differs from the standard GetMongo in that queries are executed using the last document _id retrieved and limit() to retrieve data paginated. NOTE: this is an stateful implementation, the lastId value is update with each execution. Stop component and clear the state of the Processor.")
@WritesAttributes({
    @WritesAttribute(attribute=QueryMongoCollection.ATTR_MIME_TYPE, description="Mime Type as application/json"),
    @WritesAttribute(attribute=QueryMongoCollection.ATTR_DB_NAME, description="The database where the results came from."),
    @WritesAttribute(attribute = QueryMongoCollection.ATTR_COL_NAME, description = "The collection where the results came from."),
    @WritesAttribute(attribute = "mongo.doc.[field]", description = "Fiels defined in Extract Fields property.")
    
})
@Stateful(scopes = Scope.CLUSTER, description = "After performing a query on the specified collection, the last document._id "
        + "will be retained for use in future executions of the query. This allows the Processor "
        + "to fetch only those documents that have _id values greater than the retained value. This can be used for "
        + "incremental fetching, fetching of newly added documents, etc. To clear the latest id, clear the state of the processor.")
public class QueryMongoCollection extends AbstractSessionFactoryProcessor {

    public static final String ATTR_COL_NAME = "mongo.collection.name";
    private static final String LAST_DOCUMENT_ID ="last.document.id";
    public static final String ATTR_DB_NAME = "mongo.database.name";
    public static final String ATTR_MIME_TYPE = "mime.type";
    
    public static final PropertyDescriptor URI = new PropertyDescriptor.Builder()
            .name("Mongo URI")
            .displayName("Mongo URI")
            .description("MongoURI, typically of the form: mongodb://host1[:port1][,host2[:port2],...]")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue("mongodb://localhost:27017")
            .build();
    
    private static final PropertyDescriptor DATABASE_NAME = new PropertyDescriptor.Builder()
            .name("Mongo Database Name")
            .displayName("Mongo Database Name")
            .description("The name of the database to use.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    private static final PropertyDescriptor COLLECTION_NAME = new PropertyDescriptor.Builder()
            .name("Mongo Collection Name")
            .description("The name of the collection to use.")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor LIMIT = new PropertyDescriptor.Builder()
            .name("Limit")
            .description("Max elements to retrive.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .defaultValue("50")
            .build();
    
    private static final PropertyDescriptor CHARSET = new PropertyDescriptor.Builder()
            .name("mongo-charset")
            .displayName("Character Set")
            .description("Specifies the character set of the document data.")
            .required(true)
            .defaultValue("UTF-8")
            .addValidator(StandardValidators.CHARACTER_SET_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();
    
    public static final PropertyDescriptor PROJECTION = new PropertyDescriptor.Builder()
            .name("Projection")
            .description("The fields to be returned from the documents in the result set; must be a valid BSON document.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(JsonValidator.INSTANCE)
            .build();
    
    public static final PropertyDescriptor EXTRACT_FIELDS = new PropertyDescriptor.Builder()
            .name("Extract Fields")
            .description("The fields to be extracted as mongo.doc.[field] attributes, comma separated values list.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor REMOVE_FIELDS = new PropertyDescriptor.Builder()
            .name("Remove Fields")
            .description("The fields to be removed from document, comma separated values list.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor INITIAL_DOCUMENT_ID = new PropertyDescriptor.Builder()
            .name("Initial Document Id")
            .description("Document Id to fetch documents greater than.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("All FlowFiles that have the results of a successful query execution go here.")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("All input FlowFiles that are part of a failed query execution go here.")
            .build();

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    private MongoClient mongoClient;
    private String lastId;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        this.descriptors = Collections.unmodifiableList(Arrays.asList(URI, DATABASE_NAME, COLLECTION_NAME, CHARSET, PROJECTION, LIMIT, EXTRACT_FIELDS, REMOVE_FIELDS, INITIAL_DOCUMENT_ID));
        this.relationships = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE)));
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        closeClient();
        mongoClient = new MongoClient(new MongoClientURI(getURI(context)));
        getLogger().debug("Processor scheduled");
    }
    
    @OnStopped
    public void onStopped() {
        closeClient();
        lastId = null;
        getLogger().debug("Processor stopped");
    }

    public final void onTrigger(final ProcessContext context, final ProcessSessionFactory sessionFactory) {
        final ProcessSession session = sessionFactory.createSession();
        final ComponentLog logger = getLogger();
        
        final StateManager stateManager = context.getStateManager();
        final StateMap stateMap;

        try {
            stateMap = stateManager.getState(Scope.CLUSTER);
        } catch (final IOException ioe) {
            getLogger().error("Failed to retrieve values from the State Manager.", ioe);
            context.yield();
            return;
        }
        
        lastId = stateMap.get(LAST_DOCUMENT_ID);
        // Initial doc.id present and not stored in state
        if( lastId==null && context.getProperty(INITIAL_DOCUMENT_ID).isSet() ) {
            lastId = getProperty(context, INITIAL_DOCUMENT_ID).getValue();
        }
        
        final StopWatch executionTime = new StopWatch(true);
        final MongoCollection<Document> collection = getCollection(context);
        final Map<String, String> attributes = new HashMap<>();
        attributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
        attributes.put(ATTR_DB_NAME, collection.getNamespace().getDatabaseName());
        attributes.put(ATTR_COL_NAME, collection.getNamespace().getCollectionName());
        
        final Document query = Document.parse( lastId==null ? "{}" : String.format("{'_id':{'$gt':ObjectId('%s')}}", lastId) );
        final FindIterable<Document> it = collection.find(query);
        
        final Document projection = parse(context, PROJECTION);
        if (projection != null) {
            it.projection(projection);
        }
        
        Integer limit = null;
        if(context.getProperty(LIMIT).isSet()) {
            limit = getProperty(context, LIMIT).asInteger();
            it.limit(limit);
        }
        
        final Charset charset = Charset.forName(getProperty(context, CHARSET).getValue());

        long sent = 0;
        try (MongoCursor<Document> cursor = it.iterator()) {
            while (cursor.hasNext()) {
                FlowFile flowFile = session.create();
                final Document document = cursor.next();
                ObjectId objectId = document.getObjectId("_id");
                if(objectId==null) {
                    logger.error("Document field _id not found");
                    session.transfer(flowFile, REL_FAILURE);
                } else {
                    lastId = objectId.toHexString();
                    extractFields(context, document, attributes);
                    removeFields(context, document);
                    flowFile = session.write(flowFile, out -> out.write(document.toJson().getBytes(charset)));
                    flowFile = session.putAllAttributes(flowFile, attributes);
                    session.getProvenanceReporter().receive(flowFile, getURI(context));
                    session.transfer(flowFile, REL_SUCCESS);
                    sent++;
                }
            }
            
            final long time = executionTime.getElapsed(TimeUnit.MILLISECONDS);
            logger.debug("find({}).limit({}) [sent:{}, lastId:{}, time:{}ms]", new Object[]{query.toJson(), limit, sent, lastId, time});
            
            // collection empty
            if (sent == 0) {
                context.yield();
            }
        } catch (Exception e) {
            logger.error("Unable to query database", new Object[] {e});
            context.yield();
        } finally {
            session.commit();
            try {
                // Update the state
                Map<String, String> state = Collections.singletonMap(LAST_DOCUMENT_ID, lastId);
                stateManager.setState(state, Scope.CLUSTER);
            } catch (IOException ioe) {
                getLogger().error("Failed to update State Manager, last.document.id will not be stored", new Object[]{this, ioe});
            }
        }
    }
    
    private PropertyValue getProperty(ProcessContext context, PropertyDescriptor property) {
        return context.getProperty(property).evaluateAttributeExpressions();
    }

    private String getURI(final ProcessContext context) {
        return getProperty(context, URI).getValue();
    }
    
    protected MongoCollection<Document> getCollection(final ProcessContext context) {
        final String collectionName = getProperty(context, COLLECTION_NAME).getValue();
        if (StringUtils.isEmpty(collectionName)) {
            throw new ProcessException("Collection name was empty after expression language evaluation.");
        }
        
        final String databaseName = getProperty(context, DATABASE_NAME).getValue();

        return mongoClient.getDatabase(databaseName).getCollection(collectionName);
    }
    
    private Document parse(ProcessContext context, PropertyDescriptor descriptor) {
        if(context.getProperty(descriptor).isSet()) {
            return Document.parse(getProperty(context, descriptor).getValue());
        }
        
        return null;
    }
    
    private void extractFields(ProcessContext context, Document document, Map<String, String> attributes) {
        if( context.getProperty(EXTRACT_FIELDS).isSet() ) {
            final String[] keys = getProperty(context, EXTRACT_FIELDS).getValue().replace(" ", "").split(",");
            for (String key : keys) {
                try {                                    
                    final Object value = document.get(key);
                    attributes.put("mongo.doc."+key, String.valueOf(value));
                } catch(Exception e) {
                    getLogger().error("Field {} not found", new Object[] {key});
                }
            }
        }
    }
    
    private void removeFields(ProcessContext context, Document document) {
        if( context.getProperty(REMOVE_FIELDS).isSet() ) {
            final String[] keys = getProperty(context, REMOVE_FIELDS).getValue().replace(" ", "").split(",");
            for (String key : keys) {
                document.remove(key);
            }
        }
    }
    
    private void closeClient() {
        if(mongoClient!=null) {
            mongoClient.close();
            mongoClient = null;
        }
    }
    
}
