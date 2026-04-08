package br.com.socker.integration;

import br.com.socker.adapter.in.socket.server.SocketServerAdapter;
import br.com.socker.adapter.out.logging.StructuredObservabilityAdapter;
import br.com.socker.application.usecase.ProcessReversalUseCaseImpl;
import br.com.socker.application.usecase.ProcessTransactionUseCaseImpl;
import br.com.socker.application.usecase.QueryParametersUseCaseImpl;
import br.com.socker.infrastructure.protocol.Frame;
import br.com.socker.infrastructure.protocol.FrameHeader;
import br.com.socker.infrastructure.protocol.FrameWriter;
import br.com.socker.infrastructure.protocol.IsoMessageEncoder;
import br.com.socker.domain.model.IsoMessage;
import br.com.socker.domain.model.MessageType;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static org.assertj.core.api.Assertions.*;

/**
 * Wire-level test: validates that the server response contains the 2-byte frame header
 * followed by the ASCII ISO 8583 payload.
 *
 * <p>This test bypasses FrameReader and reads raw bytes from the socket to prove
 * the protocol format unambiguously:
 *
 * <pre>
 *   Byte 0   Byte 1    Bytes 2..N
 *   [MSB]    [LSB]     [ASCII ISO 8583 payload]
 *    0x00    0xXX      "0210..."
 * </pre>
 *
 * <p>Without the header, FrameReader would misinterpret the MTI bytes "02" as
 * payload length 0x3032 = 12,338 — causing a read timeout or hang.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResponseWireFormatTest {

    private static SocketServerAdapter server;
    private static int serverPort;

    @BeforeAll
    static void startServer() throws Exception {
        server = new SocketServerAdapter(
            0, 32, 5000, 8192,
            new ProcessTransactionUseCaseImpl(),
            new ProcessReversalUseCaseImpl(),
            new QueryParametersUseCaseImpl(),
            new StructuredObservabilityAdapter(false)
        );
        server.start();
        serverPort = server.getPort();
        Thread.sleep(100);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void response_startsWithTwoByteHeader() throws Exception {
        IsoMessage request = transaction0200("000001");

        try (Socket socket = new Socket("127.0.0.1", serverPort)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // --- Send request with proper [2-byte header][payload] ---
            String requestPayload = new IsoMessageEncoder().encode(request);
            new FrameWriter().writeText(out, requestPayload);

            // --- Read raw bytes of the response — NO FrameReader ---
            // First, read the 2-byte header
            byte[] rawHeader = new byte[FrameHeader.WIRE_SIZE];
            int read = in.read(rawHeader, 0, FrameHeader.WIRE_SIZE);

            assertThat(read)
                .as("Should read exactly 2 header bytes")
                .isEqualTo(FrameHeader.WIRE_SIZE);

            // Decode the header
            FrameHeader responseHeader = FrameHeader.decode(rawHeader);
            int payloadLength = responseHeader.payloadLength();

            assertThat(payloadLength)
                .as("Payload length declared in header must be > 0")
                .isGreaterThan(0);

            assertThat(payloadLength)
                .as("Payload length must be a realistic ISO message size (< 8192)")
                .isLessThan(8192);

            // Read exactly payloadLength bytes
            byte[] payloadBytes = new byte[payloadLength];
            int totalRead = 0;
            while (totalRead < payloadLength) {
                int n = in.read(payloadBytes, totalRead, payloadLength - totalRead);
                assertThat(n)
                    .as("Stream should not end before payload is fully read")
                    .isGreaterThan(0);
                totalRead += n;
            }

            String payloadStr = new String(payloadBytes, Frame.CHARSET);

            // --- Assertions on wire content ---

            // The payload must start with the response MTI "0210"
            assertThat(payloadStr)
                .as("Response payload must start with MTI 0210")
                .startsWith("0210");

            // The declared header length must match the actual payload length
            assertThat(payloadLength)
                .as("Header-declared length must equal actual payload byte count")
                .isEqualTo(payloadBytes.length);

            // The header bytes must NOT be '0' and '2' (i.e., not the MTI "02")
            // If the header were absent, rawHeader[0..1] would be 0x30 0x32 ('0', '2')
            assertThat(rawHeader)
                .as("First byte of header must not be ASCII '0' (0x30) — if it is, the header is missing")
                .doesNotContain((byte) 0x30, (byte) 0x32);

            // Verify the response header MSB is 0x00 for payloads < 256 bytes
            // (All typical ISO 8583 test messages fit in one byte length)
            assertThat(rawHeader[0] & 0xFF)
                .as("MSB of payload length should be 0x00 for payloads < 256 bytes")
                .isEqualTo(0x00);
        }
    }

    @Test
    @Order(2)
    void header_declaredLengthMatchesPayloadBytes() throws Exception {
        IsoMessage request = transaction0200("000002");

        try (Socket socket = new Socket("127.0.0.1", serverPort)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            String requestPayload = new IsoMessageEncoder().encode(request);
            new FrameWriter().writeText(out, requestPayload);

            // Read 2-byte header
            byte[] rawHeader = new byte[FrameHeader.WIRE_SIZE];
            int headerBytesRead = 0;
            while (headerBytesRead < FrameHeader.WIRE_SIZE) {
                headerBytesRead += in.read(rawHeader, headerBytesRead, FrameHeader.WIRE_SIZE - headerBytesRead);
            }

            int payloadLength = FrameHeader.decode(rawHeader).payloadLength();

            // Read all payload bytes
            byte[] payloadBytes = new byte[payloadLength];
            int totalRead = 0;
            while (totalRead < payloadLength) {
                totalRead += in.read(payloadBytes, totalRead, payloadLength - totalRead);
            }

            // The total wire size must be header (2) + payload
            assertThat(FrameHeader.WIRE_SIZE + payloadBytes.length)
                .as("Total wire bytes = 2 (header) + declared payload length")
                .isEqualTo(FrameHeader.WIRE_SIZE + payloadLength);

            // The payload string length must match the header declaration
            String payloadStr = new String(payloadBytes, Frame.CHARSET);
            assertThat(payloadStr.length())
                .as("ASCII payload char count must equal header-declared length for pure-ASCII ISO messages")
                .isEqualTo(payloadLength);
        }
    }

    @Test
    @Order(3)
    void missingHeader_wouldCauseWrongLengthRead() {
        // Demonstrate what would happen if the header were absent:
        // The first 2 bytes of "0210..." are '0'=0x30 and '2'=0x32
        // Interpreted as Big-Endian uint16: 0x3032 = 12,338 bytes
        // That would cause a read timeout waiting for 12,338 bytes that never arrive.

        byte mtiHighByte = '0'; // 0x30
        byte mtiLowByte  = '2'; // 0x32
        int wrongLength = ((mtiHighByte & 0xFF) << 8) | (mtiLowByte & 0xFF);

        assertThat(wrongLength)
            .as("If header were missing, FrameReader would try to read this many bytes")
            .isEqualTo(12338);

        // Our server sends the correct header, so this wrong-length scenario
        // never occurs. This test documents the failure mode for clarity.
    }

    // --- helper ---

    private IsoMessage transaction0200(String nsu) {
        return IsoMessage.builder(MessageType.TRANSACTION_REQUEST)
            .field(3,  "100000")
            .field(4,  "000000001000")
            .field(7,  "0407123045")
            .field(11, nsu)
            .field(12, "123045")
            .field(13, "0407")
            .field(32, "12345678901")
            .field(40, "006")
            .field(41, "TERM0001")
            .field(42, "123456789012345")
            .field(49, "986")
            .build();
    }
}
