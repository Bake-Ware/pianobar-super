package systems.bake.pianobar;
import org.junit.Test;
import static org.junit.Assert.*;
public class ServerAddressTest {
    @Test public void acceptsGenericServers() {
        assertEquals("https://radio.example.com/", ServerAddress.normalize(" https://Radio.Example.com "));
        assertEquals("http://192.168.1.4:8765/", ServerAddress.normalize("http://192.168.1.4:8765"));
        assertEquals("http://[::1]:8765/", ServerAddress.normalize("http://[::1]:8765/"));
        assertEquals("http://music.local/", ServerAddress.normalize("http://music.local"));
    }
    @Test public void rejectsUnexpectedDestinationsAndEmbeddedSecrets() {
        for (String address : new String[]{"", "javascript:alert(1)", "file:///etc/passwd", "https://user:password@example.com", "https://example.com/#token", "https://example.com/?token=secret", "https://example.com/path", "http://example.com", "http://192.168.999.1", "https://example.com:0", "https://example.com:65536", "https://example.com\\@evil.com"}) {
            assertThrows(address, IllegalArgumentException.class, () -> ServerAddress.normalize(address));
        }
    }
}
