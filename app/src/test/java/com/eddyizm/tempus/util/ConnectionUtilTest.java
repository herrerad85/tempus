package com.eddyizm.tempus.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConnectionUtilTest {

    // One address being a text prefix of the other is not the same as sitting under it. This
    // decides which timeout a ping gets, and a bare prefix test gave the public nas.duckdns.invalid
    // the shorter one meant for a local address.
    @Test
    public void treatsANeighboringHostnameAsADifferentServer() {
        assertFalse(ConnectionUtil.isUnderAddress("http://nas.duckdns.invalid/rest/ping", "http://nas"));
    }

    @Test
    public void matchesAnAddressAndAnythingUnderIt() {
        assertTrue(ConnectionUtil.isUnderAddress("http://nas/rest/ping", "http://nas"));
        assertTrue(ConnectionUtil.isUnderAddress("http://nas", "http://nas"));
    }

    @Test
    public void toleratesATrailingSlash() {
        assertTrue(ConnectionUtil.isUnderAddress("http://nas/rest/ping", "http://nas/"));
    }

    // A server behind a reverse proxy is reached at a subpath, and that subpath is part of the
    // address.
    @Test
    public void matchesAnAddressThatCarriesAPath() {
        assertTrue(ConnectionUtil.isUnderAddress(
                "https://example.invalid/navidrome/rest/ping", "https://example.invalid/navidrome"));
        assertFalse(ConnectionUtil.isUnderAddress(
                "https://example.invalid/other/rest/ping", "https://example.invalid/navidrome"));
    }

    @Test
    public void handlesAMissingAddress() {
        assertFalse(ConnectionUtil.isUnderAddress("http://nas/rest/ping", null));
        assertFalse(ConnectionUtil.isUnderAddress("http://nas/rest/ping", ""));
        assertFalse(ConnectionUtil.isUnderAddress(null, "http://nas"));
    }

    private static final String LOCAL = "http://192.168.0.10:4533";
    private static final String PUBLIC = "https://music.example.invalid/navidrome";
    private static final List<String> ADDRESSES = Arrays.asList(LOCAL, PUBLIC);

    @Test
    public void movesALocalStreamOntoThePublicAddress() {
        assertEquals(PUBLIC + "/rest/stream?u=a&id=1",
                ConnectionUtil.moveToAddress(LOCAL + "/rest/stream?u=a&id=1", ADDRESSES, PUBLIC));
    }

    @Test
    public void movesAPublicStreamOntoTheLocalAddress() {
        assertEquals(LOCAL + "/rest/stream?id=1",
                ConnectionUtil.moveToAddress(PUBLIC + "/rest/stream?id=1", ADDRESSES, LOCAL + "/"));
    }

    @Test
    public void leavesAStreamAlreadyOnTheAddressInUse() {
        String url = PUBLIC + "/rest/stream?id=1";
        assertSame(url, ConnectionUtil.moveToAddress(url, ADDRESSES, PUBLIC));
    }

    @Test
    public void leavesAnUrlOnNeitherAddress() {
        String url = "https://radio.example.invalid/stream";
        assertSame(url, ConnectionUtil.moveToAddress(url, ADDRESSES, PUBLIC));
        assertSame(url, ConnectionUtil.moveToAddress(url, ADDRESSES, null));
    }

    @Test
    public void leavesAnUrlThatOnlySharesAPrefixWithAnAddress() {
        String url = "http://nas2/rest/stream?id=1";
        assertSame(url, ConnectionUtil.moveToAddress(url, Arrays.asList("http://nas", PUBLIC), PUBLIC));
    }

    @Test
    public void movesAStreamUnderAnAddressSavedWithATrailingSlash() {
        assertEquals(PUBLIC + "/rest/stream?id=1", ConnectionUtil.moveToAddress(
                LOCAL + "/rest/stream?id=1", Arrays.asList(LOCAL + "/", PUBLIC), PUBLIC));
    }

    @Test
    public void skipsAnAddressThatIsNotSet() {
        String url = PUBLIC + "/rest/stream?id=1";
        assertSame(url, ConnectionUtil.moveToAddress(url, Arrays.asList(null, PUBLIC), PUBLIC));
    }

    @Test
    public void picksTheLongestAddressAUrlIsUnder() {
        String local = "https://example.invalid";
        String nested = "https://example.invalid/navidrome";
        assertEquals(local + "/rest/stream?id=1", ConnectionUtil.moveToAddress(
                nested + "/rest/stream?id=1", Arrays.asList(local, nested), local));
        assertEquals(local + "/rest/stream?id=1", ConnectionUtil.moveToAddress(
                nested + "/rest/stream?id=1", Arrays.asList(nested, local), local));
    }

    @Test
    public void leavesAnUrlAlreadyUnderANestedInUseAddress() {
        String url = "https://example.invalid/navidrome/rest/stream?id=1";
        assertSame(url, ConnectionUtil.moveToAddress(url,
                Arrays.asList("https://example.invalid", "https://example.invalid/navidrome"), "https://example.invalid/navidrome"));
    }
}
