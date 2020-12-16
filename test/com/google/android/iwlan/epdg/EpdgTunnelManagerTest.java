// Copyright 2020 Google Inc. All Rights Reserved.
package com.google.android.iwlan.epdg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.InetAddresses;
import android.net.Network;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionParams;
import android.net.ipsec.ike.IkeSession;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.exceptions.IkeException;
import android.net.ipsec.ike.exceptions.InvalidIkeSpiException;
import android.os.Looper;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;

import com.google.android.iwlan.IwlanConfigs;
import com.google.android.iwlan.IwlanError;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(JUnit4.class)
public class EpdgTunnelManagerTest {
    public static final int DEFAULT_SLOT_INDEX = 0;
    public static final int DEFAULT_SUBID = 0;

    private final String TEST_IP_ADDRESS = "127.0.0.1";
    private final String TEST_IP_ADDRESS2 = "8.8.8.8";
    private final String TEST_IP_ADDRESS3 = "1.1.1.1";
    private final String TEST_APN_NAME = "www.xyz.com";
    private EpdgTunnelManager mEpdgTunnelManager;

    private class IwlanTunnelCallback implements EpdgTunnelManager.TunnelCallback {
        public void onOpened(String apnName, TunnelLinkProperties linkProperties) {}

        public void onClosed(String apnName, IwlanError error) {}
    }

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private Context mMockContext;
    @Mock private TunnelSetupRequest mMockTunnelSetupReq;
    @Mock private IwlanTunnelCallback mMockIwlanTunnelCallback;
    @Mock private IkeSession mMockIkeSession;
    @Mock private EpdgSelector mMockEpdgSelector;
    @Mock private Network mMockNetwork;
    @Mock CarrierConfigManager mMockCarrierConfigManager;
    @Mock SubscriptionManager mMockSubscriptionManager;
    @Mock SubscriptionInfo mMockSubscriptionInfo;
    @Mock TelephonyManager mMockTelephonyManager;
    @Mock EpdgTunnelManager.IkeSessionCreator mMockIkeSessionCreator;
    @Mock IkeException mMockIkeException;

    @Before
    public void setUp() throws Exception {
        mEpdgTunnelManager = spy(EpdgTunnelManager.getInstance(mMockContext, DEFAULT_SLOT_INDEX));
        doReturn(Looper.getMainLooper()).when(mEpdgTunnelManager).getLooper();
        setVariable(mEpdgTunnelManager, "mContext", mMockContext);
        mEpdgTunnelManager.initHandler();
        mEpdgTunnelManager.resetTunnelManagerState();
        when(mEpdgTunnelManager.getEpdgSelector()).thenReturn(mMockEpdgSelector);
        when(mEpdgTunnelManager.getIkeSessionCreator()).thenReturn(mMockIkeSessionCreator);

        when(mMockEpdgSelector.getValidatedServerList(
                        anyInt(),
                        anyBoolean(),
                        anyBoolean(),
                        eq(mMockNetwork),
                        any(EpdgSelector.EpdgSelectorCallback.class)))
                .thenReturn(new IwlanError(IwlanError.NO_ERROR));

        // Fake local address
        List<InetAddress> testLocalAddressList = new ArrayList<InetAddress>();
        InetAddress testInetaddr = InetAddress.getByName(TEST_IP_ADDRESS);
        testLocalAddressList.add(testInetaddr);

        doReturn(testLocalAddressList).when(mEpdgTunnelManager).getAddressForNetwork(any(), any());
    }

    @Test
    public void testBringUpTunnelWithInvalidProtocol() {
        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_PPP),
                        mMockIwlanTunnelCallback);
        assertFalse(ret);
    }

    @Test
    public void testBringUpTunnelWithInvalidPduSessionId() {
        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IPV6, 16),
                        mMockIwlanTunnelCallback);
        assertFalse(ret);

        ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IPV6, -1),
                        mMockIwlanTunnelCallback);
        assertFalse(ret);
    }

    @Test
    public void testBringUpTunnelWithValidProtocols() {
        String testApnName1 = "www.xyz.com1";
        String testApnName2 = "www.xyz.com2";
        String testApnName3 = "www.xyz.com3";

        TunnelSetupRequest TSR_v4 =
                getBasicTunnelSetupRequest(testApnName1, ApnSetting.PROTOCOL_IP);

        TunnelSetupRequest TSR_v6 =
                getBasicTunnelSetupRequest(testApnName2, ApnSetting.PROTOCOL_IPV6);

        TunnelSetupRequest TSR_v4v6 =
                getBasicTunnelSetupRequest(testApnName3, ApnSetting.PROTOCOL_IPV4V6);

        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName1));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName2));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName3));

        boolean ret = mEpdgTunnelManager.bringUpTunnel(TSR_v4, mMockIwlanTunnelCallback);
        assertTrue(ret);

        ret = mEpdgTunnelManager.bringUpTunnel(TSR_v6, mMockIwlanTunnelCallback);
        assertTrue(ret);

        ret = mEpdgTunnelManager.bringUpTunnel(TSR_v4v6, mMockIwlanTunnelCallback);
        assertTrue(ret);
    }

    @Test
    public void testBringUpTunnelWithNullApn() {

        TunnelSetupRequest TSR = getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP);

        when(mEpdgTunnelManager.getTunnelSetupRequestApnName(TSR)).thenReturn(null);

        boolean ret = mEpdgTunnelManager.bringUpTunnel(TSR, mMockIwlanTunnelCallback);
        assertFalse(ret);
        verify(mEpdgTunnelManager).getTunnelSetupRequestApnName(TSR);
    }

    @Test
    public void testBringUpTunnelWithExistApn() {
        TunnelSetupRequest TSR = getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP);

        when(mEpdgTunnelManager.isTunnelConfigContainExistApn(TEST_APN_NAME)).thenReturn(true);

        boolean ret = mEpdgTunnelManager.bringUpTunnel(TSR, mMockIwlanTunnelCallback);
        assertFalse(ret);
        verify(mEpdgTunnelManager).isTunnelConfigContainExistApn(TEST_APN_NAME);
    }

    @Test
    public void testBringUPTunnelWithNoBringUpInProcess() {
        String testApnName2 = "www.abc.com";

        TunnelSetupRequest TSR = getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP);

        mEpdgTunnelManager.putApnNameToTunnelConfig(
                testApnName2, mMockIkeSession, mMockIwlanTunnelCallback);
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(TEST_APN_NAME));

        boolean ret = mEpdgTunnelManager.bringUpTunnel(TSR, mMockIwlanTunnelCallback);
        assertTrue(ret);
    }

    @Test
    public void testBringUPTunnelSuccess() throws Exception {

        TunnelSetupRequest TSR = getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP);
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(TEST_APN_NAME));

        boolean ret = mEpdgTunnelManager.bringUpTunnel(TSR, mMockIwlanTunnelCallback);
        assertTrue(ret);
        verify(mMockEpdgSelector)
                .getValidatedServerList(
                        eq(EpdgSelector.PROTO_FILTER_IPV4),
                        eq(false),
                        eq(false),
                        eq(mMockNetwork),
                        any());
    }

    @Test
    public void testCloseTunnelWithNoTunnelForApn() throws Exception {
        String testApnName = "www.xyz.com";

        boolean ret = mEpdgTunnelManager.closeTunnel(testApnName, false /*forceClose*/);
        assertTrue(ret);
        verify(mEpdgTunnelManager).closePendingRequestsForApn(eq(testApnName));
    }

    @Test
    public void testCloseTunnelWithForceClose() throws Exception {
        String testApnName = "www.xyz.com";

        mEpdgTunnelManager.putApnNameToTunnelConfig(
                testApnName, mMockIkeSession, mMockIwlanTunnelCallback);

        boolean ret = mEpdgTunnelManager.closeTunnel(testApnName, true /*forceClose*/);
        assertTrue(ret);
        verify(mMockIkeSession).kill();
        verify(mEpdgTunnelManager).closePendingRequestsForApn(eq(testApnName));
    }

    @Test
    public void testCloseTunnelWithNonForceClose() throws Exception {
        String testApnName = "www.xyz.com";

        mEpdgTunnelManager.putApnNameToTunnelConfig(
                testApnName, mMockIkeSession, mMockIwlanTunnelCallback);

        boolean ret = mEpdgTunnelManager.closeTunnel(testApnName, false /*forceClose*/);
        assertTrue(ret);
        verify(mMockIkeSession).close();
        verify(mEpdgTunnelManager).closePendingRequestsForApn(eq(testApnName));
    }

    @Test
    public void testSetRekeyTimerFromCarrierConfig() throws Exception {
        String testApnName = "www.xyz.com";

        // Test values
        int hardTime = 50000;
        int softTime = 20000;
        int hardTimeChild = 10000;
        int softTimeChild = 1000;

        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(IwlanConfigs.KEY_IKE_REKEY_HARD_TIMER_SEC_INT, hardTime);
        bundle.putInt(IwlanConfigs.KEY_IKE_REKEY_SOFT_TIMER_SEC_INT, softTime);
        bundle.putInt(IwlanConfigs.KEY_CHILD_SA_REKEY_HARD_TIMER_SEC_INT, hardTimeChild);
        bundle.putInt(IwlanConfigs.KEY_CHILD_SA_REKEY_SOFT_TIMER_SEC_INT, softTimeChild);

        setupMockForGetConfig(bundle);

        when(mMockEpdgSelector.getValidatedServerList(
                        anyInt(),
                        eq(false),
                        eq(false),
                        eq(mMockNetwork),
                        any(EpdgSelector.EpdgSelectorCallback.class)))
                .thenReturn(new IwlanError(IwlanError.NO_ERROR));

        doReturn(null)
                .when(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        any(IkeSessionParams.class),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP),
                        mMockIwlanTunnelCallback);
        assertTrue(ret);

        ArrayList<InetAddress> ipList = new ArrayList<>();
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS));
        mEpdgTunnelManager.sendSelectionRequestComplete(
                ipList, new IwlanError(IwlanError.NO_ERROR));

        ArgumentCaptor<IkeSessionParams> ikeSessionParamsCaptor =
                ArgumentCaptor.forClass(IkeSessionParams.class);
        ArgumentCaptor<ChildSessionParams> childSessionParamsCaptor =
                ArgumentCaptor.forClass(ChildSessionParams.class);
        verify(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        ikeSessionParamsCaptor.capture(),
                        childSessionParamsCaptor.capture(),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));

        IkeSessionParams ikeSessionParams = ikeSessionParamsCaptor.getValue();
        ChildSessionParams childSessionParams = childSessionParamsCaptor.getValue();

        assertEquals(ikeSessionParams.getHardLifetimeSeconds(), hardTime);
        assertEquals(ikeSessionParams.getSoftLifetimeSeconds(), softTime);
        assertEquals(childSessionParams.getHardLifetimeSeconds(), hardTimeChild);
        assertEquals(childSessionParams.getSoftLifetimeSeconds(), softTimeChild);
    }

    @Test
    public void testSetRetransmissionTimeoutsFromCarrierConfig() throws Exception {
        String testApnName = "www.xyz.com";

        int[] testTimeouts = {1000, 1200, 1400, 1600, 2000, 4000};

        PersistableBundle bundle = new PersistableBundle();
        bundle.putIntArray(IwlanConfigs.KEY_RETRANSMIT_TIMER_MSEC_INT_ARRAY, testTimeouts);

        setupMockForGetConfig(bundle);

        when(mMockEpdgSelector.getValidatedServerList(
                        anyInt(),
                        eq(false),
                        eq(false),
                        eq(mMockNetwork),
                        any(EpdgSelector.EpdgSelectorCallback.class)))
                .thenReturn(new IwlanError(IwlanError.NO_ERROR));

        doReturn(null)
                .when(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        any(IkeSessionParams.class),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP),
                        mMockIwlanTunnelCallback);
        assertTrue(ret);

        ArrayList<InetAddress> ipList = new ArrayList<>();
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS));
        mEpdgTunnelManager.sendSelectionRequestComplete(
                ipList, new IwlanError(IwlanError.NO_ERROR));

        ArgumentCaptor<IkeSessionParams> ikeSessionParamsCaptor =
                ArgumentCaptor.forClass(IkeSessionParams.class);
        verify(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        ikeSessionParamsCaptor.capture(),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));

        IkeSessionParams ikeSessionParams = ikeSessionParamsCaptor.getValue();
        assertArrayEquals(ikeSessionParams.getRetransmissionTimeoutsMillis(), testTimeouts);
    }

    @Test
    public void testSetDpdDelayFromCarrierConfig() throws Exception {
        String testApnName = "www.xyz.com";

        // Test values
        int testDpdDelay = 600;

        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(IwlanConfigs.KEY_DPD_TIMER_SEC_INT, testDpdDelay);

        setupMockForGetConfig(bundle);
        when(mMockEpdgSelector.getValidatedServerList(
                        anyInt(),
                        eq(false),
                        eq(false),
                        eq(mMockNetwork),
                        any(EpdgSelector.EpdgSelectorCallback.class)))
                .thenReturn(new IwlanError(IwlanError.NO_ERROR));

        doReturn(null)
                .when(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        any(IkeSessionParams.class),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP),
                        mMockIwlanTunnelCallback);
        assertTrue(ret);

        ArrayList<InetAddress> ipList = new ArrayList<>();
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS));
        mEpdgTunnelManager.sendSelectionRequestComplete(
                ipList, new IwlanError(IwlanError.NO_ERROR));

        ArgumentCaptor<IkeSessionParams> ikeSessionParamsCaptor =
                ArgumentCaptor.forClass(IkeSessionParams.class);
        verify(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        ikeSessionParamsCaptor.capture(),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));

        IkeSessionParams ikeSessionParams = ikeSessionParamsCaptor.getValue();
        assertEquals(ikeSessionParams.getDpdDelaySeconds(), testDpdDelay);
    }

    @Test
    public void testTunnelRetryAfterSetupFails() throws Exception {
        String testApnName = "www.xyz.com";

        setupMockForGetConfig(null);
        when(mMockEpdgSelector.getValidatedServerList(
                        anyInt(),
                        eq(false),
                        eq(false),
                        eq(mMockNetwork),
                        any(EpdgSelector.EpdgSelectorCallback.class)))
                .thenReturn(new IwlanError(IwlanError.NO_ERROR));

        doReturn(null)
                .doReturn(null)
                .when(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        any(IkeSessionParams.class),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP),
                        mMockIwlanTunnelCallback);
        assertTrue(ret);

        ArrayList<InetAddress> ipList = new ArrayList<>();
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS2));
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS3));
        mEpdgTunnelManager.sendSelectionRequestComplete(
                ipList, new IwlanError(IwlanError.NO_ERROR));

        ArgumentCaptor<IkeSessionParams> ikeSessionParamsCaptor =
                ArgumentCaptor.forClass(IkeSessionParams.class);
        ArgumentCaptor<EpdgTunnelManager.TmIkeSessionCallback> ikeSessionCallbackCaptor =
                ArgumentCaptor.forClass(EpdgTunnelManager.TmIkeSessionCallback.class);
        verify(mMockIkeSessionCreator, atLeastOnce())
                .createIkeSession(
                        eq(mMockContext),
                        ikeSessionParamsCaptor.capture(),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        ikeSessionCallbackCaptor.capture(),
                        any(ChildSessionCallback.class));

        IkeSessionParams ikeSessionParams = ikeSessionParamsCaptor.getValue();
        assertEquals(ikeSessionParams.getServerHostname(), TEST_IP_ADDRESS2);

        // Call onclosed - which should trigger bringup tunnel for the next address
        EpdgTunnelManager.TmIkeSessionCallback ikeSessionCallback =
                ikeSessionCallbackCaptor.getValue();
        ikeSessionCallback.onClosed();

        Thread.sleep(2000);

        verify(mMockIkeSessionCreator, atLeastOnce())
                .createIkeSession(
                        eq(mMockContext),
                        ikeSessionParamsCaptor.capture(),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        ikeSessionCallbackCaptor.capture(),
                        any(ChildSessionCallback.class));
        ikeSessionParams = ikeSessionParamsCaptor.getValue();
        assertEquals(ikeSessionParams.getServerHostname(), TEST_IP_ADDRESS3);
    }

    private TunnelSetupRequest getBasicTunnelSetupRequest(String apnName, int apnIpProtocol) {
        return getBasicTunnelSetupRequest(apnName, apnIpProtocol, 1);
    }

    private TunnelSetupRequest getBasicTunnelSetupRequest(
            String apnName, int apnIpProtocol, int pduSessionId) {
        TunnelSetupRequest ret =
                TunnelSetupRequest.builder()
                        .setApnName(apnName)
                        .setNetwork(mMockNetwork)
                        .setIsRoaming(false /*isRoaming*/)
                        .setIsEmergency(false /*IsEmergency*/)
                        .setRequestPcscf(false /*requestPcscf*/)
                        .setApnIpProtocol(apnIpProtocol)
                        .setPduSessionId(pduSessionId)
                        .build();
        return ret;
    }

    private TunnelSetupRequest getHandoverTunnelSetupRequest(String apnName, int apnIpProtocol) {
        TunnelSetupRequest.Builder bld = TunnelSetupRequest.builder();
        bld.setApnName(apnName)
                .setNetwork(mMockNetwork)
                .setIsRoaming(false /*isRoaming*/)
                .setIsEmergency(false /*IsEmergency*/)
                .setRequestPcscf(false /*requestPcscf*/)
                .setApnIpProtocol(apnIpProtocol)
                .setPduSessionId(1);
        switch (apnIpProtocol) {
            case ApnSetting.PROTOCOL_IP:
                bld.setSrcIpv4Address(InetAddresses.parseNumericAddress("10.10.10.10"));
                break;
            case ApnSetting.PROTOCOL_IPV6:
                bld.setSrcIpv6Address(
                        InetAddresses.parseNumericAddress(
                                "2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
                break;
            case ApnSetting.PROTOCOL_IPV4V6:
                bld.setSrcIpv4Address(InetAddresses.parseNumericAddress("10.10.10.10"));
                bld.setSrcIpv6Address(
                        InetAddresses.parseNumericAddress(
                                "2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
                break;
        }
        return bld.build();
    }

    private void setupMockForGetConfig(PersistableBundle bundle) {
        when(mMockContext.getSystemService(eq(Context.CARRIER_CONFIG_SERVICE)))
                .thenReturn(mMockCarrierConfigManager);
        when(mMockContext.getSystemService(eq(Context.TELEPHONY_SUBSCRIPTION_SERVICE)))
                .thenReturn(mMockSubscriptionManager);
        when(mMockContext.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mMockTelephonyManager);
        when(mMockSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(DEFAULT_SLOT_INDEX))
                .thenReturn(mMockSubscriptionInfo);
        when(mMockSubscriptionInfo.getSubscriptionId()).thenReturn(DEFAULT_SUBID);
        when(mMockSubscriptionInfo.getMncString()).thenReturn("344");
        when(mMockTelephonyManager.createForSubscriptionId(DEFAULT_SUBID))
                .thenReturn(mMockTelephonyManager);
        when(mMockCarrierConfigManager.getConfigForSubId(DEFAULT_SLOT_INDEX)).thenReturn(bundle);
    }

    private void setVariable(Object target, String variableName, Object value) throws Exception {
        FieldSetter.setField(target, target.getClass().getDeclaredField(variableName), value);
    }

    @Test
    public void testHandleOnClosedWithEpdgAddressSelected_True() throws Exception {
        String testApnName = "www.xyz.com";
        IwlanError error = new IwlanError(IwlanError.NO_ERROR);

        doReturn(0L).when(mEpdgTunnelManager).reportIwlanError(eq(testApnName), eq(error));
        mEpdgTunnelManager.putApnNameToTunnelConfig(
                testApnName, mMockIkeSession, mMockIwlanTunnelCallback);

        mEpdgTunnelManager.setIsEpdgAddressSelected(true);

        mEpdgTunnelManager.getTmIkeSessionCallback(testApnName).onClosed();

        verify(mMockIwlanTunnelCallback, times(1)).onClosed(eq(testApnName), eq(error));
        verify(mEpdgTunnelManager, times(2)).resetTunnelManagerState();
        verify(mEpdgTunnelManager, times(1)).reportIwlanError(eq(testApnName), eq(error));
    }

    @Test
    public void testHandleOnClosedWithEpdgAddressSelected_False() throws Exception {
        String testApnName = "www.xyz.com";
        IwlanError error = new IwlanError(IwlanError.NO_ERROR);

        doReturn(0L).when(mEpdgTunnelManager).reportIwlanError(eq(testApnName), eq(error));

        PersistableBundle bundle = new PersistableBundle();
        setupMockForGetConfig(bundle);
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP),
                        mMockIwlanTunnelCallback);
        assertTrue(ret);

        ArrayList<InetAddress> ipList = new ArrayList<>();
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS));
        mEpdgTunnelManager.sendSelectionRequestComplete(
                ipList, new IwlanError(IwlanError.NO_ERROR));

        mEpdgTunnelManager.setIsEpdgAddressSelected(false);

        mEpdgTunnelManager.getTmIkeSessionCallback(testApnName).onClosed();

        verify(mMockIwlanTunnelCallback, times(1)).onClosed(eq(testApnName), eq(error));
        verify(mEpdgTunnelManager, times(2)).resetTunnelManagerState();
        verify(mEpdgTunnelManager, times(1)).reportIwlanError(eq(testApnName), eq(error));
    }

    @Test
    public void testHandleOnClosedExceptionallyWithEpdgAddressSelected_True() throws Exception {
        String testApnName = "www.xyz.com";
        IwlanError error = new IwlanError(mMockIkeException);

        doReturn(0L).when(mEpdgTunnelManager).reportIwlanError(eq(testApnName), eq(error));

        mEpdgTunnelManager.putApnNameToTunnelConfig(
                testApnName, mMockIkeSession, mMockIwlanTunnelCallback);

        mEpdgTunnelManager.setIsEpdgAddressSelected(true);

        mEpdgTunnelManager
                .getTmIkeSessionCallback(testApnName)
                .onClosedExceptionally(mMockIkeException);

        verify(mMockIwlanTunnelCallback, times(1)).onClosed(eq(testApnName), eq(error));
        verify(mEpdgTunnelManager, times(2)).resetTunnelManagerState();
        verify(mEpdgTunnelManager, times(1)).reportIwlanError(eq(testApnName), eq(error));
    }

    @Test
    public void testHandleOnClosedExceptionallyWithEpdgAddressSelected_False() throws Exception {
        String testApnName = "www.xyz.com";
        IwlanError error = new IwlanError(mMockIkeException);

        doReturn(0L).when(mEpdgTunnelManager).reportIwlanError(eq(testApnName), eq(error));

        PersistableBundle bundle = new PersistableBundle();
        setupMockForGetConfig(bundle);
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP),
                        mMockIwlanTunnelCallback);
        assertTrue(ret);

        ArrayList<InetAddress> ipList = new ArrayList<>();
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS));
        mEpdgTunnelManager.sendSelectionRequestComplete(
                ipList, new IwlanError(IwlanError.NO_ERROR));

        mEpdgTunnelManager.setIsEpdgAddressSelected(false);

        mEpdgTunnelManager
                .getTmIkeSessionCallback(testApnName)
                .onClosedExceptionally(mMockIkeException);

        verify(mMockIwlanTunnelCallback, times(1)).onClosed(eq(testApnName), any(IwlanError.class));
        verify(mEpdgTunnelManager, times(2)).resetTunnelManagerState();
        verify(mEpdgTunnelManager, times(1)).reportIwlanError(eq(testApnName), eq(error));
    }

    @Test
    public void testSetIkeTrafficSelectorsIPv4() throws Exception {
        testSetIkeTrafficSelectors(ApnSetting.PROTOCOL_IP, false);
    }

    @Test
    public void testSetIkeTrafficSelectorsIPv6() throws Exception {
        testSetIkeTrafficSelectors(ApnSetting.PROTOCOL_IPV6, false);
    }

    @Test
    public void testSetIkeTrafficSelectorsIPv4v6() throws Exception {
        testSetIkeTrafficSelectors(ApnSetting.PROTOCOL_IPV4V6, false);
    }

    @Test
    public void testSetIkeTrafficSelectorsIPv4_handover() throws Exception {
        testSetIkeTrafficSelectors(ApnSetting.PROTOCOL_IP, true);
    }

    @Test
    public void testSetIkeTrafficSelectorsIPv6_handover() throws Exception {
        testSetIkeTrafficSelectors(ApnSetting.PROTOCOL_IPV6, true);
    }

    @Test
    public void testSetIkeTrafficSelectorsIPv4v6_handover() throws Exception {
        testSetIkeTrafficSelectors(ApnSetting.PROTOCOL_IPV4V6, true);
    }

    private void testSetIkeTrafficSelectors(int apnProtocol, boolean handover) throws Exception {
        String testApnName = "www.xyz.com";

        PersistableBundle bundle = new PersistableBundle();
        setupMockForGetConfig(bundle);

        when(mMockEpdgSelector.getValidatedServerList(
                        anyInt(),
                        eq(false),
                        eq(false),
                        eq(mMockNetwork),
                        any(EpdgSelector.EpdgSelectorCallback.class)))
                .thenReturn(new IwlanError(IwlanError.NO_ERROR));

        doReturn(null)
                .when(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        any(IkeSessionParams.class),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        boolean ret;

        if (handover) {
            ret =
                    mEpdgTunnelManager.bringUpTunnel(
                            getHandoverTunnelSetupRequest(TEST_APN_NAME, apnProtocol),
                            mMockIwlanTunnelCallback);
        } else {
            ret =
                    mEpdgTunnelManager.bringUpTunnel(
                            getBasicTunnelSetupRequest(TEST_APN_NAME, apnProtocol),
                            mMockIwlanTunnelCallback);
        }

        assertTrue(ret);

        ArrayList<InetAddress> ipList = new ArrayList<>();
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS));
        mEpdgTunnelManager.sendSelectionRequestComplete(
                ipList, new IwlanError(IwlanError.NO_ERROR));

        ArgumentCaptor<IkeSessionParams> ikeSessionParamsCaptor =
                ArgumentCaptor.forClass(IkeSessionParams.class);
        ArgumentCaptor<ChildSessionParams> childSessionParamsCaptor =
                ArgumentCaptor.forClass(ChildSessionParams.class);
        verify(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        ikeSessionParamsCaptor.capture(),
                        childSessionParamsCaptor.capture(),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));

        ChildSessionParams childSessionParams = childSessionParamsCaptor.getValue();

        switch (apnProtocol) {
            case ApnSetting.PROTOCOL_IPV4V6:
                assertEquals(childSessionParams.getInboundTrafficSelectors().size(), 2);
                assertEquals(childSessionParams.getOutboundTrafficSelectors().size(), 2);
                assertTrue(
                        childSessionParams.getInboundTrafficSelectors().get(0).endingAddress
                                != childSessionParams
                                        .getInboundTrafficSelectors()
                                        .get(1)
                                        .endingAddress);
                assertTrue(
                        childSessionParams.getInboundTrafficSelectors().get(0).startingAddress
                                != childSessionParams
                                        .getInboundTrafficSelectors()
                                        .get(1)
                                        .startingAddress);
                break;
            case ApnSetting.PROTOCOL_IPV6:
                assertEquals(childSessionParams.getInboundTrafficSelectors().size(), 1);
                assertEquals(childSessionParams.getOutboundTrafficSelectors().size(), 1);
                assertEquals(
                        childSessionParams.getOutboundTrafficSelectors().get(0),
                        childSessionParams.getInboundTrafficSelectors().get(0));
                assertEquals(
                        childSessionParams.getInboundTrafficSelectors().get(0).endingAddress,
                        InetAddresses.parseNumericAddress(
                                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"));
                assertEquals(
                        childSessionParams.getInboundTrafficSelectors().get(0).startingAddress,
                        InetAddresses.parseNumericAddress("::"));
                break;
            case ApnSetting.PROTOCOL_IP:
                assertEquals(childSessionParams.getInboundTrafficSelectors().size(), 1);
                assertEquals(childSessionParams.getOutboundTrafficSelectors().size(), 1);
                assertEquals(
                        childSessionParams.getOutboundTrafficSelectors().get(0),
                        childSessionParams.getInboundTrafficSelectors().get(0));
                assertEquals(
                        childSessionParams.getInboundTrafficSelectors().get(0).endingAddress,
                        InetAddresses.parseNumericAddress("255.255.255.255"));
                assertEquals(
                        childSessionParams.getInboundTrafficSelectors().get(0).startingAddress,
                        InetAddresses.parseNumericAddress("0.0.0.0"));
                break;
        }
    }

    @Test
    public void testN1modeCapabilityInclusion() throws Exception {
        {
            testN1modeCapability(8);
        }
    }

    @Test
    public void testN1modeCapabilityNonInclusion() throws Exception {
        {
            testN1modeCapability(0);
        }
    }

    @Test
    public void testReportIwlanErrorIkeProtocolException() throws Exception {
        String testApnName = "www.xyz.com";
        InvalidIkeSpiException mockException = new InvalidIkeSpiException();
        IwlanError error = new IwlanError(mockException);

        doReturn(0L).when(mEpdgTunnelManager).reportIwlanError(eq(testApnName), eq(error));

        mEpdgTunnelManager.putApnNameToTunnelConfig(
                testApnName, mMockIkeSession, mMockIwlanTunnelCallback);

        mEpdgTunnelManager.setIsEpdgAddressSelected(true);

        mEpdgTunnelManager
                .getTmIkeSessionCallback(testApnName)
                .onClosedExceptionally(mockException);

        verify(mMockIwlanTunnelCallback, times(1)).onClosed(eq(testApnName), eq(error));
        verify(mEpdgTunnelManager, times(2)).resetTunnelManagerState();
        verify(mEpdgTunnelManager, times(1)).reportIwlanError(eq(testApnName), eq(error));
    }

    @Test
    public void testReportIwlanErrorServerSelectionFailed() throws Exception {
        String testApnName = "www.xyz.com";
        IwlanError error = new IwlanError(IwlanError.EPDG_SELECTOR_SERVER_SELECTION_FAILED);

        doReturn(0L).when(mEpdgTunnelManager).reportIwlanError(eq(testApnName), eq(error));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        PersistableBundle bundle = new PersistableBundle();
        setupMockForGetConfig(bundle);

        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP),
                        mMockIwlanTunnelCallback);
        assertTrue(ret);

        mEpdgTunnelManager.sendSelectionRequestComplete(null, error);
        mEpdgTunnelManager.setIsEpdgAddressSelected(false);

        verify(mEpdgTunnelManager, times(1)).reportIwlanError(eq(testApnName), eq(error));
    }

    @Test
    public void testCanBringUpTunnel() throws Exception {
        String testApnName = "www.xyz.com";
        IwlanError error = new IwlanError(mMockIkeException);

        doReturn(false).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));
        doReturn(error).when(mEpdgTunnelManager).getLastError(eq(testApnName));

        PersistableBundle bundle = new PersistableBundle();
        setupMockForGetConfig(bundle);

        boolean ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(TEST_APN_NAME, ApnSetting.PROTOCOL_IP),
                        mMockIwlanTunnelCallback);
        assertTrue(ret);
        verify(mMockIwlanTunnelCallback, times(1)).onClosed(eq(testApnName), eq(error));
    }

    private void testN1modeCapability(int pduSessionId) throws Exception {
        String testApnName = "www.xyz.com";
        int PDU_SESSION_ID = pduSessionId;
        byte PDU_SESSION_ID_BYTE = (byte) PDU_SESSION_ID;

        PersistableBundle bundle = new PersistableBundle();
        setupMockForGetConfig(bundle);

        when(mMockEpdgSelector.getValidatedServerList(
                        anyInt(),
                        eq(false),
                        eq(false),
                        eq(mMockNetwork),
                        any(EpdgSelector.EpdgSelectorCallback.class)))
                .thenReturn(new IwlanError(IwlanError.NO_ERROR));

        doReturn(null)
                .when(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        any(IkeSessionParams.class),
                        any(ChildSessionParams.class),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));
        doReturn(true).when(mEpdgTunnelManager).canBringUpTunnel(eq(testApnName));

        boolean ret;

        ret =
                mEpdgTunnelManager.bringUpTunnel(
                        getBasicTunnelSetupRequest(
                                TEST_APN_NAME, ApnSetting.PROTOCOL_IPV6, PDU_SESSION_ID),
                        mMockIwlanTunnelCallback);

        assertTrue(ret);

        ArrayList<InetAddress> ipList = new ArrayList<>();
        ipList.add(InetAddress.getByName(TEST_IP_ADDRESS));
        mEpdgTunnelManager.sendSelectionRequestComplete(
                ipList, new IwlanError(IwlanError.NO_ERROR));

        ArgumentCaptor<IkeSessionParams> ikeSessionParamsCaptor =
                ArgumentCaptor.forClass(IkeSessionParams.class);
        ArgumentCaptor<ChildSessionParams> childSessionParamsCaptor =
                ArgumentCaptor.forClass(ChildSessionParams.class);
        verify(mMockIkeSessionCreator)
                .createIkeSession(
                        eq(mMockContext),
                        ikeSessionParamsCaptor.capture(),
                        childSessionParamsCaptor.capture(),
                        any(Executor.class),
                        any(IkeSessionCallback.class),
                        any(ChildSessionCallback.class));

        IkeSessionParams ikeSessionParams = ikeSessionParamsCaptor.getValue();

        if (pduSessionId == 0) {
            assertNull(ikeSessionParams.getIke3gppExtension());
        } else {
            byte pduSessionIdByte =
                    ikeSessionParams.getIke3gppExtension().getIke3gppParams().getPduSessionId();
            assertEquals(pduSessionIdByte, PDU_SESSION_ID_BYTE);
        }
    }
}