import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints a Unity Catalog access token for a subject, signed with the key the catalog generated for
 * itself, and prints it. Used by demo-unity-up.sh to hand the demo a second provider without an
 * identity provider behind it, and by demo-embedded-up.sh to mint the one provider token that
 * demo runs with — in a real deployment each provider brings the token their catalog already
 * issued them, and nothing signs on the catalog's behalf but the catalog.
 *
 * <p>Run in source-file mode against the catalog's own classpath, which carries the JWT library:
 *
 * <pre>java -cp "$(cat classpath.txt)" MintToken.java etc/conf someone@example.com</pre>
 */
public class MintToken {

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: MintToken <unity-catalog-conf-dir> <subject>");
      System.exit(2);
    }
    Path conf = Path.of(args[0]);
    String subject = args[1];

    RSAPrivateKey key =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA")
                .generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(conf.resolve("private_key.der"))));

    System.out.println(
        JWT.create()
            .withKeyId(Files.readString(conf.resolve("key_id.txt")).trim())
            .withSubject(subject)
            .withIssuer("internal")
            .withClaim("type", "SERVICE")
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(Instant.now()))
            .sign(Algorithm.RSA256(null, key)));
  }
}
