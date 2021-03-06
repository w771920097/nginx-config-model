package com.github.t1.nginx;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.github.t1.nginx.HostPort.DEFAULT_HTTP_PORT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

@Data
@Accessors(chain = true)
public class NginxConfig {
    @SneakyThrows(MalformedURLException.class)
    public static NginxConfig readFrom(URI uri) { return readFrom(uri.toURL()); }

    static NginxConfig readFrom(URL url) {
        try (InputStream inputStream = url.openStream()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, UTF_8));
            return NginxConfigParser.parse(reader);
        } catch (IOException e) {
            throw new RuntimeException("can't load config stream from '" + url + "'", e);
        }
    }

    @NonNull private String before, after;
    @NonNull private List<NginxServer> servers;
    @NonNull private List<NginxUpstream> upstreams;

    public static NginxConfig create() {
        return new NginxConfig("http {\n    ", "}\n", new ArrayList<>(), new ArrayList<>());
    }

    @Override public String toString() {
        return before + toStrings(upstreams, "\n    ") + toStrings(servers, "") + after;
    }

    private String toStrings(List<?> list, String suffix) {
        return list.stream().map(Object::toString).collect(joining("\n    ", "", suffix));
    }


    public Stream<NginxUpstream> upstreams() { return upstreams.stream(); }

    public Optional<NginxUpstream> upstream(String name) {
        return upstreams().filter(upstream -> upstream.getName().equals(name)).findAny();
    }

    public Optional<NginxServer> server(String name, int listen) {
        return servers().filter(server -> server.getName().equals(name) && server.listen == listen).findAny();
    }

    public Stream<NginxServer> servers() { return servers.stream(); }

    public void removeUpstream(NginxUpstream upstream) {
        upstreams.remove(upstream);
    }

    public void removeUpstream(String name) {
        upstreams.removeIf(upstream -> upstream.getName().equals(name));
    }

    public NginxConfig addUpstream(NginxUpstream upstream) {
        upstreams.add(upstream);
        upstreams.sort(null);
        return this;
    }

    public void removeServer(NginxServer server) {
        servers.remove(server);
    }

    public void removeServer(HostPort hostPort) {
        servers.removeIf(hostPort::matches);
    }

    public NginxConfig addServer(NginxServer server) {
        servers.add(server);
        servers.sort(null);
        return this;
    }


    /** https://www.nginx.com/resources/admin-guide/load-balancer/ */
    @Data
    @AllArgsConstructor
    public static class NginxUpstream implements Comparable<NginxUpstream> {
        private static final String PREFIX = "        server ";
        private static final String SUFFIX = ";\n";

        @NonNull private String before;
        @NonNull private String after;
        @NonNull private String name;
        private String method;
        private List<HostPort> hostPorts;

        public static NginxUpstream named(String name) {
            return new NginxUpstream("", "", name, null, new ArrayList<>());
        }

        @Override public int compareTo(NginxUpstream that) { return this.name.compareTo(that.name); }

        @Override public String toString() {
            return "upstream " + name + " {\n"
                + (before.isEmpty() ? "" : "        " + before + "\n")
                + ((method == null) ? "" : "        " + method + ";\n\n")
                + ((hostPorts == null) ? ""
                : hostPorts.stream().map(HostPort::toString)
                .collect(joining(SUFFIX + PREFIX, PREFIX, SUFFIX)))
                + (after.isEmpty() ? "" : "        " + after + "\n")
                + "    }\n";
        }

        public boolean isEmpty() { return hostPorts.isEmpty(); }

        public Stream<HostPort> hostPorts() { return hostPorts.stream(); }

        public boolean hasHost(String host) {
            return hostPorts().anyMatch(hostPort -> hostPort.getHost().equals(host));
        }

        public void removeHost(String host) {
            hostPorts.removeIf(hostPort -> hostPort.getHost().equals(host));
        }

        public NginxUpstream addHostPort(HostPort hostPort) {
            hostPorts.add(hostPort);
            hostPorts.sort(null);
            return this;
        }

        public void updateHostPort(HostPort hostPort) {
            removeHost(hostPort.getHost());
            addHostPort(hostPort);
        }

        public void setPort(HostPort hostPort, int port) {
            int index = hostPorts.indexOf(hostPort);
            if (index < 0)
                throw new IllegalArgumentException("can't find " + hostPort + " in " + this);
            hostPorts.set(index, hostPort.withPort(port));
            hostPorts.sort(null);
        }

        public int port(String host) {
            return hostPorts()
                .filter(hostPort -> hostPort.getHost().equals(host))
                .findAny()
                .map(HostPort::getPort)
                .orElseThrow(() -> new IllegalStateException("no server for " + host + " in upstream " + name));
        }

        public int indexOf(String host) {
            for (int i = 0; i < hostPorts.size(); i++)
                if (hostPorts.get(i).getHost().equals(host))
                    return i;
            throw new IllegalArgumentException("host [" + host + "] not in " + hostPorts);
        }
    }

    @Data
    @AllArgsConstructor
    public static class NginxServer implements Comparable<NginxServer> {
        @NonNull private String name;
        private int listen;
        @NonNull private List<NginxServerLocation> locations;

        public static NginxServer named(String name) {
            return new NginxServer(name, DEFAULT_HTTP_PORT, new ArrayList<>());
        }

        @Override public int compareTo(NginxServer that) { return this.name.compareTo(that.name); }


        @Override public String toString() {
            return "server {\n"
                + "        server_name " + name + ";\n"
                + "        listen " + listen + ";\n"
                + locations.stream().map(NginxServerLocation::toString).collect(joining())
                + "    }\n";
        }


        public Optional<NginxServerLocation> location(String name) {
            return locations().filter(location -> location.getName().equals(name)).findAny();
        }

        private Stream<NginxServerLocation> locations() { return locations.stream(); }

        public NginxServer addLocation(NginxServerLocation location) {
            locations.add(location);
            locations.sort(null);
            return this;
        }
    }

    @Data
    @AllArgsConstructor
    public static class NginxServerLocation implements Comparable<NginxServerLocation> {
        @NonNull private String before;
        @NonNull private String after;
        @NonNull private String name;
        private URI proxyPass;

        public static NginxServerLocation named(String name) {
            return new NginxServerLocation("", "", name, null);
        }

        @Override public int compareTo(NginxServerLocation that) { return this.name.compareTo(that.name); }

        @Override public String toString() {
            return "        location " + name + " {\n"
                + (before.isEmpty() ? "" : "            " + before + "\n")
                + "            proxy_pass " + proxyPass + ";\n"
                + (after.isEmpty() ? "" : "            " + after + "\n")
                + "        }\n";
        }
    }
}
