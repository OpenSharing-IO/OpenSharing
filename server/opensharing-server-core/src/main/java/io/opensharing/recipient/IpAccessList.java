package io.opensharing.recipient;

import io.opensharing.http.ApiException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CIDR matching for a recipient's IP allowlist.
 *
 * <p>The address checked is the one the connection came from. Behind a proxy or load balancer that is
 * the proxy's address, so an allowlist is only meaningful when the server is reached directly or the
 * proxy is trusted to preserve the peer address.
 */
public final class IpAccessList {

  private IpAccessList() {}

  /** Rejects anything that is not a CIDR block or a bare address, and trims what it accepts. */
  static List<String> validate(List<String> cidrs) {
    List<String> validated = new ArrayList<>();
    for (String cidr : cidrs) {
      if (cidr == null || cidr.isBlank()) {
        throw ApiException.invalidParameter("ip_access_list must not contain blank entries");
      }
      String trimmed = cidr.trim();
      parse(trimmed)
          .orElseThrow(
              () -> ApiException.invalidParameter("'" + trimmed + "' is not a valid CIDR block"));
      validated.add(trimmed);
    }
    return validated;
  }

  /** An empty allowlist allows everything, which is what a recipient starts with. */
  public static boolean allows(List<String> cidrs, String address) {
    if (cidrs.isEmpty()) {
      return true;
    }
    InetAddress candidate = address(address);
    if (candidate == null) {
      return false;
    }
    return cidrs.stream()
        .map(IpAccessList::parse)
        .anyMatch(block -> block.isPresent() && block.get().contains(candidate));
  }

  private static Optional<Block> parse(String cidr) {
    int slash = cidr.indexOf('/');
    String host = slash < 0 ? cidr : cidr.substring(0, slash);
    InetAddress network = address(host);
    if (network == null) {
      return Optional.empty();
    }
    int maxBits = network.getAddress().length * 8;
    int bits = maxBits;
    if (slash >= 0) {
      try {
        bits = Integer.parseInt(cidr.substring(slash + 1));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
      if (bits < 0 || bits > maxBits) {
        return Optional.empty();
      }
    }
    return Optional.of(new Block(network.getAddress(), bits));
  }

  /** Parses a numeric address only: a hostname here would mean a DNS lookup on every request. */
  private static InetAddress address(String value) {
    boolean ipv6 = value.indexOf(':') >= 0;
    boolean hasLetters = value.chars().anyMatch(Character::isLetter);
    if (value.isBlank() || (hasLetters && !ipv6)) {
      return null;
    }
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private record Block(byte[] network, int bits) {

    boolean contains(InetAddress candidate) {
      byte[] address = candidate.getAddress();
      if (address.length != network.length) {
        return false;
      }
      int fullBytes = bits / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (address[i] != network[i]) {
          return false;
        }
      }
      int remaining = bits % 8;
      if (remaining == 0) {
        return true;
      }
      int mask = 0xFF << (8 - remaining);
      return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }
  }
}
