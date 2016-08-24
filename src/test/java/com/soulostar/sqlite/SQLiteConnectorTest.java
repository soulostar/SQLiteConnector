package com.soulostar.sqlite;

import static com.soulostar.sqlite.SQLiteConnector.IN_MEMORY_SUBNAME;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sqlite.SQLiteConfig;

public class SQLiteConnectorTest {
	
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();
	
	@Rule
	public ExpectedException thrown = ExpectedException.none();

	@BeforeClass
	public static void beforeClass() throws ClassNotFoundException, IOException {
		Class.forName("org.sqlite.JDBC");
		RedirectedStderr.init();
	}
	
	@After
	public void deleteTestDatabase() throws IOException {
		Files.deleteIfExists(Paths.get(getTempDbPath()));
	}

	@Test
	public void getConnection_sameConnectionForInMemoryDatabase() throws SQLException, IOException {
		SQLiteConnector connector = buildDefaultConnector();
		
		try (Connection conn = connector.getConnection(IN_MEMORY_SUBNAME)) {
			try (Connection conn1 = connector.getConnection(IN_MEMORY_SUBNAME)) {
				try (Connection conn2 = connector.getConnection(IN_MEMORY_SUBNAME)) {
					assertTrue(
							"Multiple requests for in-memory database connection "
									+ "should return the same connection object",
							conn == conn1 && conn1 == conn2);
				}
			}
		}
	}
	
	@Test
	public void getConnection_sameConnectionForSameFile() throws SQLException, IOException {
		SQLiteConnector connector = buildDefaultConnector();
		
		String tempDbPath = getTempDbPath();
		try (Connection conn = connector.getConnection(tempDbPath)) {
			try (Connection conn1 = connector.getConnection(tempDbPath)) {
				try (Connection conn2 = connector.getConnection(tempDbPath)) {
					assertTrue(
							"Multiple requests for a connection to the same database "
									+ "file should return the same connection object",
							conn == conn1 && conn1 == conn2);
				}
			}	
		}
	}
	
	@Test
	public void getConnection_sameConnectionForEquivalentPaths() throws IOException, SQLException {
		SQLiteConnector connector = buildDefaultConnector();
		
		// This test has to operate on a special temp directory other than
		// the TemporaryFolder, because we have to test relative paths in addition
		// to absolute/canonical paths.
		Path tmpDir = Paths.get("tmp");
		Path relative = Paths.get("tmp", "test.db");
		Path relative1 = Paths.get("tmp", "..", "tmp", "test.db");
		Path absolute = Paths.get("tmp", "..", "tmp", "test.db").toAbsolutePath();
		Path canonical = Paths.get("tmp", "test.db").toAbsolutePath();
		try {
			Files.createDirectory(tmpDir);
			try (Connection conn = connector.getConnection(relative.toString())) {
				try (Connection conn1 = connector.getConnection(relative1.toString())) {
					try (Connection conn2 = connector.getConnection(absolute.toString())) {
						try (Connection conn3 = connector.getConnection(canonical.toString())) {
							assertTrue(
									"Connection requests to a database using equivalent "
											+ "relative/absolute/canonical paths should "
											+ "return the same connection object",
									conn == conn1 && conn1 == conn2 && conn2 == conn3);
						}
					}
				}
			}
		} finally {
			Files.deleteIfExists(relative);
			Files.deleteIfExists(relative1);
			Files.deleteIfExists(absolute);
			Files.deleteIfExists(canonical);
			Files.deleteIfExists(tmpDir);
		}
	}
	
	@Test
	public void getConnection_sameConnectionForMultipleConcurrentThreads() throws InterruptedException, SQLException, IOException {
		SQLiteConnector connector = buildDefaultConnector();
		
		AtomicInteger identicalConnections = new AtomicInteger();
		ExecutorService executor = Executors.newCachedThreadPool();
		int threadTestCount = 100;
		try (Connection conn = connector.getConnection(getTempDbPath())) {
			for (int i = 0; i < threadTestCount; i++) {
				executor.execute(new IdenticalConnectionTest(connector, identicalConnections, conn));				
			}
			executor.shutdown();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertTrue("", identicalConnections.get() == threadTestCount);
	}
	
	private class IdenticalConnectionTest implements Runnable {
		
		final SQLiteConnector connector;
		final AtomicInteger identicalConnectionCounter;
		final Connection connToCompare;
		
		IdenticalConnectionTest(SQLiteConnector connector, AtomicInteger identicalConnectionCounter,
				Connection connToCompare) {
			this.connector = connector;
			this.identicalConnectionCounter = identicalConnectionCounter;
			this.connToCompare = connToCompare;
		}

		@Override
		public void run() {
			try (Connection conn = connector.getConnection(getTempDbPath())) {
				// Sleep for a short random time to encourage more concurrent connection requests
				Thread.sleep((long)(Math.random() * 1000));
				if (conn == connToCompare) {
					identicalConnectionCounter.incrementAndGet();
				}
			} catch (@SuppressWarnings("unused") SQLException | IOException | InterruptedException ex) {
			}
		}
		
	}
	
	@Test
	public void getConnection_differentConnectionForDifferentFiles() throws SQLException, IOException {
		SQLiteConnector connector = buildDefaultConnector();
		
		try (Connection conn = connector.getConnection(getTempDbPath())) {
			String otherTempDbPath = getTempFilePath("Test1.db");
			try (Connection conn1 = connector.getConnection(otherTempDbPath)) {
				assertFalse("Connections to different databases should not be identical", conn == conn1);
			} finally {
				Files.deleteIfExists(Paths.get(otherTempDbPath));
			}
		}
	}
	
	@Test
	public void getConnection_differentConnectionForSequentialRequests() throws SQLException, IOException {
		SQLiteConnector connector = buildDefaultConnector();
		
		int requestCount = 100;
		Set<Connection> openedConnections = new HashSet<>();
		for (int i = 0; i < requestCount; i++) {
			try (Connection conn = connector.getConnection(getTempDbPath())) {
				openedConnections.add(conn);
			}
		}			
		assertTrue("Sequential requests for a given database should return a new connection object each time",
				openedConnections.size() == requestCount);
	}
	
	@Test
	public void getConnection_createsDatabaseByDefault() throws SQLException, IOException {
		SQLiteConnector connector = buildDefaultConnector();

		Path db = Paths.get(getTempDbPath());
		assertTrue("Test database should not exist before getting connection", Files.notExists(db));	
		try (Connection conn = connector.getConnection(getTempDbPath())) {
		}	
		assertTrue("Test database should have been created by getting connection", Files.exists(db));
	}
	
	@Test
	public void getConnection_throwsWhenCreateIsOff() throws SQLException, IOException {
		SQLiteConnector connector = SQLiteConnectorBuilder.newBuilder().cannotCreateDatabases().build();

		thrown.expect(FileNotFoundException.class);
		try (Connection conn = connector.getConnection(getTempDbPath())) {
		}
	}
	
	@Test
	public void getConnection_logsWhenLoggingIsOn() throws SQLException, IOException {
		SQLiteConnector connector = SQLiteConnectorBuilder.newBuilder().withLogging(SQLiteConnectorTest.class).build();
		
		assertTrue("Logging should occur when connector is configured to log.", connectorDoesLog(connector));
	}
	
	@Test
	public void getConnection_doesNotLogByDefault() throws SQLException, IOException {
		SQLiteConnector connector = buildDefaultConnector();
		
		assertFalse("Logging should not occur by default.", connectorDoesLog(connector));
	}
	
	/**
	 * Checks if logging occurs when getting connections via a number of
	 * different methods with the given connector.
	 * 
	 * @param connector
	 *            - the connector to test
	 * @return true if logging occurred; false if not.
	 * @throws SQLException
	 *             if a database access error occurs
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	private boolean connectorDoesLog(SQLiteConnector connector) throws SQLException, IOException {
		RedirectedStderr.bytesOut.reset();
		
		// Get connections in a bunch of different ways, and then check
		// if System.err has written anything (the slf4j-simple binding logs to
		// System.err).
		try (Connection conn = connector.getConnection(getTempDbPath())) {
			try (Connection innerConn = connector.getConnection(getTempDbPath(), false)) {	
			}	
			try (Connection innerConn = connector.getUnsharedConnection(getTempDbPath())) {
			}
		}
		try (Connection con = connector.getUnsharedConnection(getTempDbPath(), new Properties())) {
		}
	
		return RedirectedStderr.bytesOut.toByteArray().length > 0;
	}

	@Test
	public void getConnection_usesPropertiesWhenConfigured() throws SQLException, IOException {
		SQLiteConfig config = new SQLiteConfig();
		config.enforceForeignKeys(true);
		SQLiteConnector connector = SQLiteConnectorBuilder
				.newBuilder()
				.withConnectionProperties(config.toProperties())
				.build();
		
		try (Connection conn = connector.getConnection(getTempDbPath())) {
			try (Statement statement = conn.createStatement()) {
				statement.executeUpdate("CREATE TABLE fk_source (FKID INT PRIMARY KEY)");
				statement.executeUpdate("INSERT INTO fk_source VALUES(1)");
				statement.executeUpdate("CREATE TABLE fk_user (PKID INT PRIMARY KEY REFERENCES fk_source(FKID))");
				statement.executeUpdate("INSERT INTO fk_user VALUES(1)");
				thrown.expectMessage("foreign key constraint failed");
				statement.executeUpdate("DELETE FROM fk_source");
			}
		}
	}

	/**
	 * Builds and returns a default <code>SQLiteConnector</code>.
	 * 
	 * @return a default <code>SQLiteConnector</code>.
	 */
	private SQLiteConnector buildDefaultConnector() {
		return SQLiteConnectorBuilder.newBuilder().build();
	}
	
	/**
	 * Gets the path of the main test database, located in this class's
	 * <code>TemporaryFolder</code>. This test database is automatically cleaned up
	 * after each test.
	 * 
	 * @return the path of the main test database.
	 */
	private String getTempDbPath() {
		return getTempFilePath("Test.db");
	}
	
	/**
	 * Gets the path of a temporary file in this class's
	 * <code>TemporaryFolder</code>.
	 * 
	 * @param filename
	 *            - the name of the file
	 * @return
	 */
	private String getTempFilePath(String filename) {
		return folder.getRoot().getAbsolutePath() + File.separator + filename;
	}

}
