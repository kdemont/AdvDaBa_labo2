package mse.advDB;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import static org.neo4j.driver.Values.parameters;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

public class Example {

    private static final String NEO4J_USER = "neo4j";
    private static final String NEO4J_PASSWORD = "test";
    private static final AtomicLong HTTP_RETRY_WAIT_MS = new AtomicLong(0);
    private static final AtomicLong HTTP_RETRY_COUNT = new AtomicLong(0);
    private static final AtomicLong HTTP_CHUNK_COUNT = new AtomicLong(0);

    public static void main(String[] args) throws Exception {
        String jsonPath = getenv("JSON_FILE", "/file.jsonl");
        String neo4jIp = getenv("NEO4J_IP", "localhost");

        int maxArticles = Integer.parseInt(getenv("MAX_ARTICLES", "10000"));
        int batchSize = Integer.parseInt(getenv("BATCH_SIZE", "500"));
        int apocBatchSize = batchSize / 10; // ajuster la taille des lots pour APOC si nécessaire

        Instant start = Instant.now();

        System.out.println("=== DBLP LOADER START ===");
        System.out.println("Start time: " + start);
        System.out.println("JSON file: " + jsonPath);
        System.out.println("Neo4j IP: " + neo4jIp);
        System.out.println("Max articles to read: " + maxArticles);
        System.out.println("Batch size: " + batchSize);
        System.out.println("APOC batch size: " + apocBatchSize);
        Driver driver = GraphDatabase.driver(
                "bolt://" + neo4jIp + ":7687",
                AuthTokens.basic(NEO4J_USER, NEO4J_PASSWORD)
        );

        waitForNeo4j(driver);

        try (Session session = driver.session()) {
            createConstraints(session);

            System.out.println("Pass 1/2: loading ARTICLE, AUTHOR and AUTHORED...");
            long inputArticles = loadArticlesAndAuthors(session, jsonPath, maxArticles, batchSize, apocBatchSize);

            System.out.println("Pass 2/2: loading CITES relationships...");
            loadCitations(session, jsonPath, maxArticles, batchSize, apocBatchSize);

            long articleCount = count(session, "MATCH (a:ARTICLE) RETURN count(a) AS c");
            long authorCount = count(session, "MATCH (a:AUTHOR) RETURN count(a) AS c");
            long authoredCount = count(session, "MATCH ()-[r:AUTHORED]->() RETURN count(r) AS c");
            long citesCount = count(session, "MATCH ()-[r:CITES]->() RETURN count(r) AS c");

            Instant end = Instant.now();
            long durationSeconds = Duration.between(start, end).getSeconds();

            System.out.println("=== DBLP LOADER END ===");
            System.out.println("End time: " + end);
            long httpRetryWaitSeconds = HTTP_RETRY_WAIT_MS.get() / 1000;
            long effectiveDurationSeconds = durationSeconds - httpRetryWaitSeconds;

            System.out.println("Duration seconds: " + durationSeconds);
            System.out.println("HTTP retry count: " + HTTP_RETRY_COUNT.get());
            System.out.println("HTTP chunk count: " + HTTP_CHUNK_COUNT.get());
            System.out.println("HTTP retry wait seconds: " + httpRetryWaitSeconds);
            System.out.println("Effective loading seconds excluding HTTP retry waits: " + effectiveDurationSeconds);
            System.out.println("Input article lines read: " + inputArticles);
            System.out.println("Articles loaded: " + articleCount);
            System.out.println("Authors loaded: " + authorCount);
            System.out.println("Total nodes loaded: " + (articleCount + authorCount));
            System.out.println("AUTHORED relationships loaded: " + authoredCount);
            System.out.println("CITES relationships loaded: " + citesCount);
        } finally {
            driver.close();
        }
    }

    private static void waitForNeo4j(Driver driver) throws InterruptedException {
        boolean connected = false;

        while (!connected) {
            try {
                System.out.println("Waiting for Neo4j...");
                Thread.sleep(5000);
                driver.verifyConnectivity();
                connected = true;
                System.out.println("Connected to Neo4j.");
            } catch (Exception e) {
                System.out.println("Neo4j not ready yet.");
            }
        }
    }

    private static void createConstraints(Session session) {
        session.writeTransaction(tx -> {
            tx.run("CREATE CONSTRAINT article_id IF NOT EXISTS " +
                    "FOR (a:ARTICLE) REQUIRE a._id IS UNIQUE").consume();

            tx.run("CREATE CONSTRAINT author_id IF NOT EXISTS " +
                    "FOR (a:AUTHOR) REQUIRE a._id IS UNIQUE").consume();

            return null;
        });

        System.out.println("Constraints created or already existing.");
    }

    private static long loadArticlesAndAuthors(
            Session session,
            String jsonPath,
            int maxArticles,
            int batchSize,
            int apocBatchSize
    ) throws IOException {

        ArrayList<Map<String, Object>> articleBatch = new ArrayList<>();
        ArrayList<Map<String, Object>> authorBatch = new ArrayList<>();
        ArrayList<Map<String, Object>> authoredBatch = new ArrayList<>();

        long articlesRead = 0;

        try (BufferedReader br = openReader(jsonPath)) {
            String line;

            while ((line = br.readLine()) != null) {
                if (maxArticles > 0 && articlesRead >= maxArticles) {
                    break;
                }

                JsonObject article = parseJsonObject(line);
                String articleId = getString(article, "id");

                if (articleId.isEmpty()) {
                    continue;
                }

                String title = getString(article, "title");

                Map<String, Object> articleRow = new HashMap<>();
                articleRow.put("id", articleId);
                articleRow.put("title", title);
                articleBatch.add(articleRow);

                if (article.containsKey("authors") && !article.isNull("authors")) {
                    JsonArray authors = article.getJsonArray("authors");

                    for (int i = 0; i < authors.size(); i++) {
                        JsonObject author = authors.getJsonObject(i);

                        String authorId = buildAuthorId(author);
                        String authorName = getString(author, "name");

                        if (authorId.isEmpty() || authorName.isEmpty()) {
                            continue;
                        }

                        Map<String, Object> authorRow = new HashMap<>();
                        authorRow.put("id", authorId);
                        authorRow.put("name", authorName);
                        authorBatch.add(authorRow);

                        Map<String, Object> authoredRow = new HashMap<>();
                        authoredRow.put("authorId", authorId);
                        authoredRow.put("articleId", articleId);
                        authoredBatch.add(authoredRow);
                    }
                }

                articlesRead++;

                if (articleBatch.size() >= batchSize) {
                    flushArticles(session, articleBatch, apocBatchSize);
                    flushAuthors(session, authorBatch, apocBatchSize);
                    flushAuthored(session, authoredBatch, apocBatchSize);

                    if (articlesRead % (batchSize * 10L) == 0) {
                        System.out.println("Pass 1 progress - articles read: " + articlesRead);
                    }
                }
            }
        }

        flushArticles(session, articleBatch, apocBatchSize);
        flushAuthors(session, authorBatch, apocBatchSize);
        flushAuthored(session, authoredBatch, apocBatchSize);

        return articlesRead;
    }

    private static void loadCitations(
            Session session,
            String jsonPath,
            int maxArticles,
            int batchSize,
            int apocBatchSize
    ) throws IOException {

        ArrayList<Map<String, Object>> citationBatch = new ArrayList<>();

        long articlesRead = 0;

        try (BufferedReader br = openReader(jsonPath)) {
            String line;

            while ((line = br.readLine()) != null) {
                if (maxArticles > 0 && articlesRead >= maxArticles) {
                    break;
                }

                JsonObject article = parseJsonObject(line);
                String sourceId = getString(article, "id");

                if (sourceId.isEmpty()) {
                    continue;
                }

                if (article.containsKey("references") && !article.isNull("references")) {
                    JsonArray references = article.getJsonArray("references");

                    for (int i = 0; i < references.size(); i++) {
                        String targetId = references.getString(i, "");

                        if (targetId.isEmpty()) {
                            continue;
                        }

                        Map<String, Object> citationRow = new HashMap<>();
                        citationRow.put("sourceId", sourceId);
                        citationRow.put("targetId", targetId);
                        citationBatch.add(citationRow);
                    }
                }

                articlesRead++;

                if (citationBatch.size() >= batchSize) {
                    flushCitations(session, citationBatch, apocBatchSize);

                    if (articlesRead % (batchSize * 10L) == 0) {
                        System.out.println("Pass 2 progress - articles read: " + articlesRead);
                    }
                }
            }
        }

        flushCitations(session, citationBatch, apocBatchSize);
    }

    private static void flushArticles(Session session, List<Map<String, Object>> rows, int apocBatchSize) {
        if (rows.isEmpty()) return;

        session.run(
                "CALL apoc.periodic.iterate(" +
                "  'UNWIND $rows AS row RETURN row'," +
                "  'CREATE (a:ARTICLE {_id: row.id, title: row.title})'," +
                "  {batchSize: $apocBatchSize, parallel: true, params: {rows: $rows}}"
                + ")",
                parameters("rows", rows, "apocBatchSize", apocBatchSize)
            ).consume();

        rows.clear();
    }

    private static void flushAuthors(Session session, ArrayList<Map<String, Object>> rows, int apocBatchSize) {
        if (rows.isEmpty()) {
            return;
        }

        session.writeTransaction(tx -> {
            tx.run(
                "UNWIND $rows AS row " +
                "MERGE (a:AUTHOR {_id: row.id}) " +
                "ON CREATE SET a.name = row.name",
                parameters("rows", rows)
            ).consume();
            return null;
        });

        rows.clear();
    }

    private static void flushAuthored(Session session, ArrayList<Map<String, Object>> rows, int apocBatchSize) {
        if (rows.isEmpty()) {
            return;
        }

        session.run(
                    "CALL apoc.periodic.iterate(" +
                    "  'UNWIND $rows AS row RETURN row'," +
                    "  'WITH DISTINCT row.authorId AS authorId, row.articleId AS articleId " +
                    "   MATCH (author:AUTHOR {_id: authorId}) " +
                    "   MATCH (article:ARTICLE {_id: articleId}) " +
                    "   CREATE (author)-[:AUTHORED]->(article)'," +
                    "  {batchSize: $apocBatchSize, parallel: true, params: {rows: $rows}}"
                    + ")",
                    parameters("rows", rows, "apocBatchSize", apocBatchSize)
            ).consume();

        rows.clear();
    }

    private static void flushCitations(Session session, ArrayList<Map<String, Object>> rows, int apocBatchSize) {
        if (rows.isEmpty()) {
            return;
        }

        session.run(
                    "CALL apoc.periodic.iterate(" +
                    "  'UNWIND $rows AS row RETURN row'," +
                    "  'WITH DISTINCT row.sourceId AS sourceId, row.targetId AS targetId " +
                    "   MATCH (source:ARTICLE {_id: sourceId}) " +
                    "   MATCH (target:ARTICLE {_id: targetId}) " +
                    "   CREATE (source)-[:CITES]->(target)'," +
                    "  {batchSize: $apocBatchSize, parallel: true, params: {rows: $rows}}"
                    + ")",
                    parameters("rows", rows, "apocBatchSize", apocBatchSize)
            ).consume();   

        rows.clear();
    }

    private static long count(Session session, String query) {
        return session.readTransaction(tx -> {
            Result result = tx.run(query);
            return result.single().get("c").asLong();
        });
    }

    private static JsonObject parseJsonObject(String line) {
        try (JsonReader reader = Json.createReader(new StringReader(line))) {
            return reader.readObject();
        }
    }

    private static String getString(JsonObject object, String key) {
        if (!object.containsKey(key) || object.isNull(key)) {
            return "";
        }

        try {
            return object.getString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String buildAuthorId(JsonObject author) {
        String rawId = getString(author, "id");

        if (!rawId.trim().isEmpty()) {
            return rawId.trim();
        }

        String name = getString(author, "name").trim();

        if (name.isEmpty()) {
            return "";
        }

        return "generated-author-id:" + name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static BufferedReader openReader(String source) throws IOException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            System.out.println("Opening chunked remote JSONL stream: " + source);
            return new BufferedReader(
                    new InputStreamReader(
                            new ChunkedHttpInputStream(source),
                            StandardCharsets.UTF_8
                    )
            );
        }

        System.out.println("Opening local JSONL file: " + source);
        return Files.newBufferedReader(Paths.get(source), StandardCharsets.UTF_8);
    }

    private static String getenv(String key, String defaultValue) {
        String value = System.getenv(key);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return value.trim();
    }

    private static class ChunkedHttpInputStream extends InputStream {
        private static final int DEFAULT_CHUNK_SIZE_MB = 16;
        private static final int MAX_CONSECUTIVE_RETRIES = 50;
        private static final int CONNECT_TIMEOUT_MS = 30_000;
        private static final int READ_TIMEOUT_MS = 120_000;

        private final String source;
        private final int chunkSizeBytes;

        private byte[] currentChunk = new byte[0];
        private int currentChunkOffset = 0;
        private int currentChunkLength = 0;

        private long position = 0;
        private int consecutiveRetries = 0;
        private long totalRetries = 0;
        private boolean eof = false;
        private boolean closed = false;

        ChunkedHttpInputStream(String source) {
            this.source = source;

            int chunkSizeMb;
            try {
                chunkSizeMb = Integer.parseInt(getenv("HTTP_CHUNK_SIZE_MB", String.valueOf(DEFAULT_CHUNK_SIZE_MB)));
            } catch (Exception e) {
                chunkSizeMb = DEFAULT_CHUNK_SIZE_MB;
            }

            this.chunkSizeBytes = chunkSizeMb * 1024 * 1024;

            System.out.println("HTTP chunk size MB: " + chunkSizeMb);
        }

        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            int result = read(oneByte, 0, 1);

            if (result == -1) {
                return -1;
            }

            return oneByte[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }

            if (eof) {
                return -1;
            }

            if (currentChunkOffset >= currentChunkLength) {
                loadNextChunk();

                if (eof) {
                    return -1;
                }
            }

            int available = currentChunkLength - currentChunkOffset;
            int bytesToCopy = Math.min(length, available);

            System.arraycopy(currentChunk, currentChunkOffset, buffer, offset, bytesToCopy);

            currentChunkOffset += bytesToCopy;
            position += bytesToCopy;

            return bytesToCopy;
        }

        private void loadNextChunk() throws IOException {
            while (true) {
                try {
                    currentChunk = fetchChunk(position);
                    currentChunkOffset = 0;
                    currentChunkLength = currentChunk.length;

                    if (currentChunkLength == 0) {
                        eof = true;
                        return;
                    }

                    long chunkNumber = HTTP_CHUNK_COUNT.incrementAndGet();

                    if (chunkNumber % 100 == 0) {
                        System.out.println("HTTP chunks loaded: " + chunkNumber + ", current byte position: " + position);
                    }

                    if (consecutiveRetries > 0) {
                        System.out.println("HTTP stream recovered. Consecutive retries reset to 0.");
                    }

                    consecutiveRetries = 0;
                    return;
                } catch (IOException exception) {
                    retryAfterFailure(exception);
                }
            }
        }

        private byte[] fetchChunk(long startByte) throws IOException {
            long endByte = startByte + chunkSizeBytes - 1;

            HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();

            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
            );
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Range", "bytes=" + startByte + "-" + endByte);

            try {
                int responseCode = connection.getResponseCode();

                if (responseCode == 416) {
                    return new byte[0];
                }

                if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException(
                            "Expected HTTP 206 Partial Content for range "
                                    + startByte
                                    + "-"
                                    + endByte
                                    + ", got HTTP "
                                    + responseCode
                    );
                }

                long expectedContentLength = connection.getContentLengthLong();

                try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream(chunkSizeBytes)) {

                    byte[] tmp = new byte[64 * 1024];
                    int read;

                    while ((read = input.read(tmp)) != -1) {
                        output.write(tmp, 0, read);
                    }

                    byte[] data = output.toByteArray();

                    if (expectedContentLength > 0 && data.length < expectedContentLength) {
                        throw new IOException(
                                "Incomplete HTTP chunk at byte "
                                        + startByte
                                        + ": expected "
                                        + expectedContentLength
                                        + " bytes, got "
                                        + data.length
                        );
                    }

                    return data;
                }
            } finally {
                connection.disconnect();
            }
        }

        private void retryAfterFailure(IOException cause) throws IOException {
            while (true) {
                if (consecutiveRetries >= MAX_CONSECUTIVE_RETRIES) {
                    throw new IOException(
                         "HTTP chunk loading failed after "
                                    + consecutiveRetries
                                    + " consecutive retries at byte "
                                    + position
                                    + " (total retries during this stream: "
                                    + totalRetries
                                    + ")",
                            cause
                    );
                }

                consecutiveRetries++;
                totalRetries++;

                long sleepMs = Math.min(30_000L, 1_000L * consecutiveRetries);

                HTTP_RETRY_COUNT.incrementAndGet();
                HTTP_RETRY_WAIT_MS.addAndGet(sleepMs);

                System.out.println(
                        "HTTP chunk loading interrupted at byte "
                                + position
                                + " with "
                                + cause.getClass().getSimpleName()
                                + ": "
                                + cause.getMessage()
                                + ". Consecutive retry "
                                + consecutiveRetries
                                + "/"
                                + MAX_CONSECUTIVE_RETRIES
                                + ", total retries for this stream "
                                + totalRetries
                                + ". Waiting "
                                + sleepMs
                                + " ms..."
                );

                try {
                    Thread.sleep(sleepMs);
                    return;
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting before HTTP retry", interruptedException);
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            currentChunk = new byte[0];
            currentChunkOffset = 0;
            currentChunkLength = 0;
        }
    }
}