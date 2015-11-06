/*
 * Copyright 2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.client.gridfs;

import com.mongodb.MongoClient;
import com.mongodb.MongoGridFSException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.model.GridFSDownloadByNameOptions;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import static com.mongodb.ReadPreference.primary;
import static com.mongodb.assertions.Assertions.notNull;
import static java.lang.String.format;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

@SuppressWarnings("deprecation")
final class GridFSBucketImpl implements GridFSBucket {
    private final MongoDatabase database;
    private final String bucketName;
    private final int chunkSizeBytes;
    private final WriteConcern writeConcern;
    private final ReadPreference readPreference;
    private final MongoCollection<Document> filesCollection;
    private final MongoCollection<Document> chunksCollection;
    private final CodecRegistry codecRegistry;
    private volatile boolean checkedIndexes;

    GridFSBucketImpl(final MongoDatabase database) {
        this(database, "fs");
    }

    GridFSBucketImpl(final MongoDatabase database, final String bucketName) {
        this.database = notNull("database", database);
        this.bucketName = notNull("bucketName", bucketName);
        this.chunkSizeBytes = 255 * 1024;
        this.writeConcern = database.getWriteConcern();
        this.readPreference = database.getReadPreference();
        this.codecRegistry = getCodecRegistry();
        this.filesCollection = getFilesCollection();
        this.chunksCollection = getChunksCollection();
    }

    GridFSBucketImpl(final MongoDatabase database, final String bucketName, final int chunkSizeBytes, final CodecRegistry codecRegistry,
                     final ReadPreference readPreference, final WriteConcern writeConcern, final MongoCollection<Document> filesCollection,
                     final MongoCollection<Document> chunksCollection, final boolean checkedIndexes) {
        this.database = notNull("database", database);
        this.bucketName = notNull("bucketName", bucketName);
        this.chunkSizeBytes = chunkSizeBytes;
        this.codecRegistry = notNull("codecRegistry", codecRegistry);
        this.readPreference = notNull("readPreference", readPreference);
        this.writeConcern = notNull("writeConcern", writeConcern);
        this.checkedIndexes = checkedIndexes;
        this.filesCollection =  notNull("filesCollection", filesCollection);
        this.chunksCollection = notNull("chunksCollection", chunksCollection);
    }

    @Override
    public String getBucketName() {
        return bucketName;
    }

    @Override
    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    @Override
    public ReadPreference getReadPreference() {
        return readPreference;
    }

    @Override
    public WriteConcern getWriteConcern() {
        return writeConcern;
    }

    @Override
    public GridFSBucket withChunkSizeBytes(final int chunkSizeBytes) {
        return new GridFSBucketImpl(database, bucketName, chunkSizeBytes, codecRegistry, readPreference, writeConcern, filesCollection,
                chunksCollection, checkedIndexes);
    }

    @Override
    public GridFSBucket withReadPreference(final ReadPreference readPreference) {
        return new GridFSBucketImpl(database, bucketName, chunkSizeBytes, codecRegistry, readPreference, writeConcern, filesCollection,
                chunksCollection, checkedIndexes);
    }

    @Override
    public GridFSBucket withWriteConcern(final WriteConcern writeConcern) {
        return new GridFSBucketImpl(database, bucketName, chunkSizeBytes, codecRegistry, readPreference, writeConcern, filesCollection,
                chunksCollection, checkedIndexes);
    }

    @Override
    public GridFSUploadStream openUploadStream(final String filename) {
        return openUploadStream(filename, new GridFSUploadOptions());
    }

    @Override
    public GridFSUploadStream openUploadStream(final String filename, final GridFSUploadOptions options) {
        int chunkSize = options.getChunkSizeBytes() == null ? chunkSizeBytes : options.getChunkSizeBytes();
        checkCreateIndex();
        return new GridFSUploadStreamImpl(filesCollection, chunksCollection, new ObjectId(), filename, chunkSize, options.getMetadata());
    }

    @Override
    public ObjectId uploadFromStream(final String filename, final InputStream source) {
        return uploadFromStream(filename, source, new GridFSUploadOptions());
    }

    @Override
    public ObjectId uploadFromStream(final String filename, final InputStream source, final GridFSUploadOptions options) {
        GridFSUploadStream uploadStream = openUploadStream(filename, options);
        int chunkSize = options.getChunkSizeBytes() == null ? chunkSizeBytes : options.getChunkSizeBytes();
        byte[] buffer = new byte[chunkSize];
        int len;
        try {
            while ((len = source.read(buffer)) != -1) {
                uploadStream.write(buffer, 0, len);
            }
            uploadStream.close();
        } catch (IOException e) {
            uploadStream.abort();
            throw new MongoGridFSException("IOException when reading from the InputStream", e);
        }
        return uploadStream.getFileId();
    }

    @Override
    public GridFSDownloadStream openDownloadStream(final ObjectId id) {
        return findTheFileInfoAndOpenDownloadStream(new BsonObjectId(id));
    }

    @Override
    public void downloadToStream(final ObjectId id, final OutputStream destination) {
        downloadToStream(findTheFileInfoAndOpenDownloadStream(new BsonObjectId(id)), destination);
    }

    @Override
    public void downloadToStream(final BsonValue id, final OutputStream destination) {
        downloadToStream(findTheFileInfoAndOpenDownloadStream(id), destination);
    }

    @Override
    public GridFSDownloadStream openDownloadStream(final BsonValue id) {
        return findTheFileInfoAndOpenDownloadStream(id);
    }

    @Override
    public GridFSDownloadStream openDownloadStreamByName(final String filename) {
        return openDownloadStreamByName(filename, new GridFSDownloadByNameOptions());
    }

    @Override
    public GridFSDownloadStream openDownloadStreamByName(final String filename, final GridFSDownloadByNameOptions options) {
        return new GridFSDownloadStreamImpl(getFileByName(filename, options), chunksCollection);
    }

    @Override
    public void downloadToStreamByName(final String filename, final OutputStream destination) {
        downloadToStreamByName(filename, destination, new GridFSDownloadByNameOptions());
    }

    @Override
    public void downloadToStreamByName(final String filename, final OutputStream destination, final GridFSDownloadByNameOptions options) {
        downloadToStream(openDownloadStreamByName(filename, options), destination);
    }

    @Override
    public GridFSFindIterable find() {
        return new GridFSFindIterableImpl(filesCollection.find());
    }

    @Override
    public GridFSFindIterable find(final Bson filter) {
        return find().filter(filter);
    }

    @Override
    public void delete(final ObjectId id) {
        DeleteResult result = filesCollection.deleteOne(new BsonDocument("_id", new BsonObjectId(id)));
        chunksCollection.deleteMany(new BsonDocument("files_id", new BsonObjectId(id)));

        if (result.wasAcknowledged() && result.getDeletedCount() == 0) {
            throw new MongoGridFSException(format("No file found with the ObjectId: %s", id));
        }
    }

    @Override
    public void rename(final ObjectId id, final String newFilename) {
        UpdateResult updateResult = filesCollection.updateOne(new Document("_id", id),
                new Document("$set", new Document("filename", newFilename)));

        if (updateResult.wasAcknowledged() && updateResult.getMatchedCount() == 0) {
            throw new MongoGridFSException(format("No file found with the ObjectId: %s", id));
        }
    }

    @Override
    public void drop() {
        filesCollection.drop();
        chunksCollection.drop();
    }

    private CodecRegistry getCodecRegistry() {
        return fromRegistries(database.getCodecRegistry(), MongoClient.getDefaultCodecRegistry());
    }

    private MongoCollection<Document> getFilesCollection() {
        return database.getCollection(bucketName + ".files")
                .withCodecRegistry(codecRegistry)
                .withReadPreference(readPreference)
                .withWriteConcern(writeConcern);
    }

    private MongoCollection<Document> getChunksCollection() {
        return database.getCollection(bucketName + ".chunks")
                .withCodecRegistry(MongoClient.getDefaultCodecRegistry())
                .withReadPreference(readPreference)
                .withWriteConcern(writeConcern);
    }

    private void checkCreateIndex() {
        if (!checkedIndexes) {
            if (filesCollection.withReadPreference(primary()).find().projection(new Document("_id", 1)).first() == null) {
                Document filesIndex = new Document("filename", 1).append("uploadDate", 1);
                if (!hasIndex(filesCollection.withReadPreference(primary()), filesIndex)) {
                    filesCollection.createIndex(filesIndex);
                }
                Document chunksIndex = new Document("files_id", 1).append("n", 1);
                if (!hasIndex(chunksCollection.withReadPreference(primary()), chunksIndex)) {
                    chunksCollection.createIndex(chunksIndex, new IndexOptions().unique(true));
                }
            }
            checkedIndexes = true;
        }
    }

    private boolean hasIndex(final MongoCollection<Document> collection, final Document index) {
        boolean hasIndex = false;
        ArrayList<Document> indexes = collection.listIndexes().into(new ArrayList<Document>());
        for (Document indexDoc : indexes) {
            if (indexDoc.get("key", Document.class).equals(index)) {
                hasIndex = true;
                break;
            }
        }
        return hasIndex;
    }

    private GridFSFile getFileByName(final String filename, final GridFSDownloadByNameOptions options) {
        int revision = options.getRevision();
        int skip;
        int sort;
        if (revision >= 0) {
            skip = revision;
            sort = 1;
        } else {
            skip = (-revision) - 1;
            sort = -1;
        }

        GridFSFile fileInfo = find(new Document("filename", filename)).skip(skip).sort(new Document("uploadDate", sort)).first();
        if (fileInfo == null) {
            throw new MongoGridFSException(format("No file found with the filename: %s and revision: %s", filename, revision));
        }
        return fileInfo;
    }

    private GridFSDownloadStream findTheFileInfoAndOpenDownloadStream(final BsonValue id) {
        GridFSFile fileInfo = find(new Document("_id", id)).first();
        if (fileInfo == null) {
            throw new MongoGridFSException(format("No file found with the id: %s", id));
        }
        return new GridFSDownloadStreamImpl(fileInfo, chunksCollection);
    }

    private void downloadToStream(final GridFSDownloadStream downloadStream, final OutputStream destination) {
        byte[] buffer = new byte[downloadStream.getGridFSFile().getChunkSize()];
        int len;
        MongoGridFSException savedThrowable = null;
        try {
            while ((len = downloadStream.read(buffer)) != -1) {
                destination.write(buffer, 0, len);
            }
        } catch (IOException e) {
            savedThrowable = new MongoGridFSException("IOException when reading from the OutputStream", e);
        } catch (Exception e) {
            savedThrowable = new MongoGridFSException("Unexpected Exception when reading GridFS and writing to the Stream", e);
        } finally {
            try {
                downloadStream.close();
            } catch (Exception e) {
                // Do nothing
            }
            if (savedThrowable != null) {
                throw savedThrowable;
            }
        }
    }
}
