// Copyright 2020 Google Inc. All Rights Reserved.

package com.google.android.iwlan;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.*;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.DataFailCause;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.data.DataCallResponse;
import android.telephony.data.DataProfile;
import android.telephony.data.DataService;
import android.telephony.data.DataServiceCallback;
import android.telephony.data.IDataServiceCallback;

import com.google.android.iwlan.IwlanDataService.IwlanDataServiceProvider;
import com.google.android.iwlan.IwlanDataService.IwlanDataServiceProvider.IwlanTunnelCallback;
import com.google.android.iwlan.IwlanDataService.IwlanDataServiceProvider.TunnelState;
import com.google.android.iwlan.IwlanDataService.IwlanWifiMonitorCallback;
import com.google.android.iwlan.epdg.EpdgTunnelManager;
import com.google.android.iwlan.epdg.TunnelLinkProperties;
import com.google.android.iwlan.epdg.TunnelLinkPropertiesTest;
import com.google.android.iwlan.epdg.TunnelSetupRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class IwlanDataServiceTest {
    private static final int DEFAULT_SLOT_INDEX = 0;
    private static final int LINK_MTU = 1280;
    private static final String TEST_APN_NAME = "ims";
    private static final String IP_ADDRESS = "192.0.2.1";
    private static final String DNS_ADDRESS = "8.8.8.8";
    private static final String GATEWAY_ADDRESS = "0.0.0.0";
    private static final String PSCF_ADDRESS = "10.159.204.230";
    private static final String INTERFACE_NAME = "ipsec6";

    @Mock private Context mMockContext;
    @Mock private ConnectivityManager mMockConnectivityManager;
    @Mock private DataServiceCallback mMockDataServiceCallback;
    @Mock private EpdgTunnelManager mMockEpdgTunnelManager;
    @Mock private IwlanDataServiceProvider mMockIwlanDataServiceProvider;
    @Mock private Network mMockNetwork;
    @Mock private TunnelLinkProperties mMockTunnelLinkProperties;
    @Mock private ErrorPolicyManager mMockErrorPolicyManager;
    MockitoSession mStaticMockSession;

    private List<DataCallResponse> mResultDataCallList;
    private @DataServiceCallback.ResultCode int mResultCode;
    private CountDownLatch latch;
    private IwlanDataService mIwlanDataService;
    private IwlanDataServiceProvider mIwlanDataServiceProvider;
    private IwlanDataServiceProvider mSpyIwlanDataServiceProvider;

    private final class IwlanDataServiceCallback extends IDataServiceCallback.Stub {

        private final String mTag;

        IwlanDataServiceCallback(String tag) {
            mTag = tag;
        }

        @Override
        public void onSetupDataCallComplete(
                @DataServiceCallback.ResultCode int resultCode, DataCallResponse response) {}

        @Override
        public void onDeactivateDataCallComplete(@DataServiceCallback.ResultCode int resultCode) {}

        @Override
        public void onSetInitialAttachApnComplete(@DataServiceCallback.ResultCode int resultCode) {}

        @Override
        public void onSetDataProfileComplete(@DataServiceCallback.ResultCode int resultCode) {}

        @Override
        public void onRequestDataCallListComplete(
                @DataServiceCallback.ResultCode int resultCode,
                List<DataCallResponse> dataCallList) {
            mResultCode = resultCode;
            mResultDataCallList = new ArrayList<DataCallResponse>(dataCallList);
            latch.countDown();
        }

        @Override
        public void onDataCallListChanged(List<DataCallResponse> dataCallList) {}

        @Override
        public void onHandoverStarted(@DataServiceCallback.ResultCode int result) {}

        @Override
        public void onHandoverCancelled(@DataServiceCallback.ResultCode int result) {}

        @Override
        public void onApnUnthrottled(String apn) {}
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mStaticMockSession =
                mockitoSession()
                        .mockStatic(ErrorPolicyManager.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();

        when(mMockContext.getSystemService(eq(ConnectivityManager.class)))
                .thenReturn(mMockConnectivityManager);

        mIwlanDataService = new IwlanDataService();
        mIwlanDataService.setAppContext(mMockContext);
        mIwlanDataServiceProvider =
                (IwlanDataServiceProvider)
                        mIwlanDataService.onCreateDataServiceProvider(DEFAULT_SLOT_INDEX);
        mSpyIwlanDataServiceProvider = spy(mIwlanDataServiceProvider);
    }

    @After
    public void cleanUp() throws Exception {
        mStaticMockSession.finishMocking();
        if (mIwlanDataService != null) {
            mIwlanDataService.onDestroy();
        }
    }

    @Test
    public void testWifiOnAvailable() {
        IwlanWifiMonitorCallback mWifiMonitorCallBack = mIwlanDataService.getWifiMonitorCallback();

        mWifiMonitorCallBack.onAvailable(mMockNetwork);
        boolean ret = mIwlanDataService.isWifiConnected();

        assertTrue(ret);
    }

    @Test
    public void testWifiOnLost() {
        mIwlanDataService.addIwlanDataServiceProvider(mMockIwlanDataServiceProvider);
        IwlanWifiMonitorCallback mWifiMonitorCallBack = mIwlanDataService.getWifiMonitorCallback();

        mWifiMonitorCallBack.onLost(mMockNetwork);
        boolean ret = mIwlanDataService.isWifiConnected();

        assertFalse(ret);
        verify(mMockIwlanDataServiceProvider).forceCloseTunnelsInDeactivatingState();
    }

    @Test
    public void testRequestDataCallListPass() throws Exception {
        DataProfile dp = buildDataProfile();
        List<LinkAddress> mInternalAddressList;
        List<InetAddress> mDNSAddressList;
        List<InetAddress> mGatewayAddressList;
        List<InetAddress> mPCSFAddressList;

        latch = new CountDownLatch(1);
        IwlanDataServiceCallback callback = new IwlanDataServiceCallback("requestDataCallList");
        TunnelLinkProperties mLinkProperties =
                TunnelLinkPropertiesTest.createTestTunnelLinkProperties();
        mIwlanDataServiceProvider.setTunnelState(
                dp,
                new DataServiceCallback(callback),
                TunnelState.TUNNEL_UP,
                mLinkProperties,
                false);
        mIwlanDataServiceProvider.requestDataCallList(new DataServiceCallback(callback));
        latch.await(1, TimeUnit.SECONDS);

        assertEquals(mResultCode, DataServiceCallback.RESULT_SUCCESS);
        assertEquals(mResultDataCallList.size(), 1);
        for (DataCallResponse dataCallInfo : mResultDataCallList) {
            assertEquals(dataCallInfo.getId(), TEST_APN_NAME.hashCode());
            assertEquals(dataCallInfo.getLinkStatus(), DataCallResponse.LINK_STATUS_ACTIVE);
            assertEquals(dataCallInfo.getProtocolType(), ApnSetting.PROTOCOL_IPV4V6);
            assertEquals(dataCallInfo.getInterfaceName(), INTERFACE_NAME);

            mInternalAddressList = dataCallInfo.getAddresses();
            assertEquals(mInternalAddressList.size(), 1);
            for (LinkAddress mLinkAddress : mInternalAddressList) {
                assertEquals(mLinkAddress, new LinkAddress(InetAddress.getByName(IP_ADDRESS), 3));
            }

            mDNSAddressList = dataCallInfo.getDnsAddresses();
            assertEquals(mDNSAddressList.size(), 1);
            for (InetAddress mInetAddress : mDNSAddressList) {
                assertEquals(mInetAddress, InetAddress.getByName(DNS_ADDRESS));
            }

            mGatewayAddressList = dataCallInfo.getGatewayAddresses();
            assertEquals(mGatewayAddressList.size(), 1);
            for (InetAddress mInetAddress : mGatewayAddressList) {
                assertEquals(mInetAddress, Inet4Address.getByName(GATEWAY_ADDRESS));
            }

            mPCSFAddressList = dataCallInfo.getPcscfAddresses();
            assertEquals(mPCSFAddressList.size(), 1);
            for (InetAddress mInetAddress : mPCSFAddressList) {
                assertEquals(mInetAddress, InetAddress.getByName(PSCF_ADDRESS));
            }

            assertEquals(dataCallInfo.getMtuV4(), LINK_MTU);
            assertEquals(dataCallInfo.getMtuV6(), LINK_MTU);
        }
    }

    @Test
    public void testRequestDataCallListEmpty() throws Exception {
        latch = new CountDownLatch(1);
        IwlanDataServiceCallback callback = new IwlanDataServiceCallback("requestDataCallList");
        mIwlanDataServiceProvider.requestDataCallList(new DataServiceCallback(callback));
        latch.await(1, TimeUnit.SECONDS);

        assertEquals(mResultCode, DataServiceCallback.RESULT_SUCCESS);
        assertEquals(mResultDataCallList.size(), 0);
    }

    @Test
    public void testIwlanSetupDataCallWithInvalidArg() {
        mIwlanDataServiceProvider.setupDataCall(
                AccessNetworkType.UNKNOWN, /* AccessNetworkType */
                null, /* dataProfile */
                false, /* isRoaming */
                true, /* allowRoaming */
                DataService.REQUEST_REASON_NORMAL, /* DataService.REQUEST_REASON_NORMAL */
                null, /* LinkProperties */
                2, /* pdu session id */
                mMockDataServiceCallback);

        verify(mMockDataServiceCallback, timeout(1000).times(1))
                .onSetupDataCallComplete(
                        eq(DataServiceCallback.RESULT_ERROR_INVALID_ARG), isNull());
    }

    @Test
    public void testIwlanSetupDataCallWithIllegalState() {
        DataProfile dp = buildDataProfile();

        /* Wifi is not connected */
        mIwlanDataService.setWifiConnected(false, mMockNetwork);

        mIwlanDataServiceProvider.setupDataCall(
                AccessNetworkType.IWLAN, /* AccessNetworkType */
                dp, /* dataProfile */
                false, /* isRoaming */
                true, /* allowRoaming */
                DataService.REQUEST_REASON_NORMAL, /* DataService.REQUEST_REASON_NORMAL */
                null, /* LinkProperties */
                1, /* pdu session id */
                mMockDataServiceCallback);

        verify(mMockDataServiceCallback, timeout(1000).times(1))
                .onSetupDataCallComplete(
                        eq(DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE), isNull());
    }

    @Test
    public void testIwlanDeactivateDataCallWithInvalidArg() {
        mIwlanDataServiceProvider.deactivateDataCall(
                0, /* cid */
                DataService.REQUEST_REASON_NORMAL, /* DataService.REQUEST_REASON_NORMAL */
                mMockDataServiceCallback);

        verify(mMockDataServiceCallback, timeout(1000).times(1))
                .onDeactivateDataCallComplete(eq(DataServiceCallback.RESULT_ERROR_INVALID_ARG));
    }

    @Test
    public void testIwlanSetupDataCallWithBringUpTunnel() {
        DataProfile dp = buildDataProfile();

        /* Wifi is connected */
        mIwlanDataService.setWifiConnected(true, mMockNetwork);

        doReturn(mMockEpdgTunnelManager).when(mSpyIwlanDataServiceProvider).getTunnelManager();

        mSpyIwlanDataServiceProvider.setupDataCall(
                AccessNetworkType.IWLAN, /* AccessNetworkType */
                dp, /* dataProfile */
                false, /* isRoaming */
                true, /* allowRoaming */
                DataService.REQUEST_REASON_NORMAL, /* DataService.REQUEST_REASON_NORMAL */
                null, /* LinkProperties */
                1, /* pduSessionId */
                mMockDataServiceCallback);

        /* Check bringUpTunnel() is called. */
        verify(mMockEpdgTunnelManager, times(1))
                .bringUpTunnel(any(TunnelSetupRequest.class), any(IwlanTunnelCallback.class));

        /* Check callback result is RESULT_SUCCESS when onOpened() is called. */
        mSpyIwlanDataServiceProvider
                .getIwlanTunnelCallback()
                .onOpened(TEST_APN_NAME, mMockTunnelLinkProperties);
        verify(mMockDataServiceCallback, times(1))
                .onSetupDataCallComplete(
                        eq(DataServiceCallback.RESULT_SUCCESS), any(DataCallResponse.class));
    }

    @Test
    public void testIwlanDeactivateDataCallWithCloseTunnel() {
        DataProfile dp = buildDataProfile();

        doReturn(mMockEpdgTunnelManager).when(mSpyIwlanDataServiceProvider).getTunnelManager();

        mSpyIwlanDataServiceProvider.setTunnelState(
                dp, mMockDataServiceCallback, TunnelState.TUNNEL_IN_BRINGUP, null, false);

        mSpyIwlanDataServiceProvider.deactivateDataCall(
                TEST_APN_NAME.hashCode() /* cid: hashcode() of "ims" */,
                DataService.REQUEST_REASON_NORMAL /* DataService.REQUEST_REASON_NORMAL */,
                mMockDataServiceCallback);

        /* Check closeTunnel() is called. */
        verify(mMockEpdgTunnelManager, times(1)).closeTunnel(eq(TEST_APN_NAME), anyBoolean());

        /* Check callback result is RESULT_SUCCESS when onClosed() is called. */
        mSpyIwlanDataServiceProvider
                .getIwlanTunnelCallback()
                .onClosed(TEST_APN_NAME, new IwlanError(IwlanError.NO_ERROR));
        verify(mMockDataServiceCallback, times(1))
                .onDeactivateDataCallComplete(eq(DataServiceCallback.RESULT_SUCCESS));
    }

    @Test
    public void testHandoverFailureModeNormal() {
        DataProfile dp = buildDataProfile();
        int setupDataReason = DataService.REQUEST_REASON_NORMAL;

        when(ErrorPolicyManager.getInstance(eq(mMockContext), eq(DEFAULT_SLOT_INDEX)))
                .thenReturn(mMockErrorPolicyManager);
        when(mMockErrorPolicyManager.getCurrentRetryTime(eq(TEST_APN_NAME))).thenReturn(5L);
        when(mMockErrorPolicyManager.getDataFailCause(eq(TEST_APN_NAME)))
                .thenReturn(DataFailCause.USER_AUTHENTICATION);

        mSpyIwlanDataServiceProvider.setTunnelState(
                dp,
                mMockDataServiceCallback,
                TunnelState.TUNNEL_IN_BRINGUP,
                null,
                (setupDataReason == DataService.REQUEST_REASON_HANDOVER));

        mSpyIwlanDataServiceProvider
                .getIwlanTunnelCallback()
                .onClosed(TEST_APN_NAME, new IwlanError(IwlanError.NO_ERROR));

        ArgumentCaptor<DataCallResponse> dataCallResponseCaptor =
                ArgumentCaptor.forClass(DataCallResponse.class);

        verify(mMockDataServiceCallback, times(1))
                .onSetupDataCallComplete(
                        eq(DataServiceCallback.RESULT_SUCCESS), dataCallResponseCaptor.capture());

        DataCallResponse dataCallResponse = dataCallResponseCaptor.getValue();
        assertEquals(
                dataCallResponse.getHandoverFailureMode(),
                DataCallResponse.HANDOVER_FAILURE_MODE_NO_FALLBACK_RETRY_SETUP_NORMAL);
        assertEquals(dataCallResponse.getCause(), DataFailCause.USER_AUTHENTICATION);
        assertEquals(dataCallResponse.getRetryDurationMillis(), 5L);
    }

    @Test
    public void testHandoverFailureModeHandover() {
        DataProfile dp = buildDataProfile();
        int setupDataReason = DataService.REQUEST_REASON_HANDOVER;

        when(ErrorPolicyManager.getInstance(eq(mMockContext), eq(DEFAULT_SLOT_INDEX)))
                .thenReturn(mMockErrorPolicyManager);
        when(mMockErrorPolicyManager.getCurrentRetryTime(eq(TEST_APN_NAME))).thenReturn(-1L);
        when(mMockErrorPolicyManager.getDataFailCause(eq(TEST_APN_NAME)))
                .thenReturn(DataFailCause.ERROR_UNSPECIFIED);

        mSpyIwlanDataServiceProvider.setTunnelState(
                dp,
                mMockDataServiceCallback,
                TunnelState.TUNNEL_IN_BRINGUP,
                null,
                (setupDataReason == DataService.REQUEST_REASON_HANDOVER));

        mSpyIwlanDataServiceProvider
                .getIwlanTunnelCallback()
                .onClosed(TEST_APN_NAME, new IwlanError(IwlanError.NO_ERROR));

        ArgumentCaptor<DataCallResponse> dataCallResponseCaptor =
                ArgumentCaptor.forClass(DataCallResponse.class);

        verify(mMockDataServiceCallback, times(1))
                .onSetupDataCallComplete(
                        eq(DataServiceCallback.RESULT_SUCCESS), dataCallResponseCaptor.capture());

        DataCallResponse dataCallResponse = dataCallResponseCaptor.getValue();
        assertEquals(
                dataCallResponse.getHandoverFailureMode(),
                DataCallResponse.HANDOVER_FAILURE_MODE_NO_FALLBACK_RETRY_HANDOVER);
        assertEquals(dataCallResponse.getCause(), DataFailCause.ERROR_UNSPECIFIED);
        assertEquals(dataCallResponse.getRetryDurationMillis(), -1L);
    }

    private DataProfile buildDataProfile() {
        DataProfile dp =
                new DataProfile.Builder()
                        .setProfileId(1)
                        .setApn(TEST_APN_NAME)
                        .setProtocolType(ApnSetting.PROTOCOL_IPV4V6) // IPv4v6
                        .setAuthType(0) // none
                        .setUserName("")
                        .setPassword("")
                        .setType(1) // 3gpp
                        // .setMaxConnectionsTime(1)
                        // .setMaxConnections(3)
                        // .setWaitTime(10)
                        .enable(true)
                        .setSupportedApnTypesBitmask(ApnSetting.TYPE_IMS)
                        .setRoamingProtocolType(ApnSetting.PROTOCOL_IPV4V6) // IPv4v6
                        .setBearerBitmask((int) TelephonyManager.NETWORK_TYPE_BITMASK_IWLAN)
                        .setPersistent(true)
                        .setPreferred(true)
                        .build();
        return dp;
    }
}