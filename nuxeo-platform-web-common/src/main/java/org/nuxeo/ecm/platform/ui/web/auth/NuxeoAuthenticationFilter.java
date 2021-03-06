/*
 * (C) Copyright 2006-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thierry Delprat
 *     Bogdan Stefanescu
 *     Anahide Tchertchian
 *     Florent Guillaume
 */

package org.nuxeo.ecm.platform.ui.web.auth;

import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.DISABLE_REDIRECT_REQUEST_KEY;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.ERROR_AUTHENTICATION_FAILED;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.ERROR_CONNECTION_FAILED;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.FORCE_ANONYMOUS_LOGIN;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.FORM_SUBMITTED_MARKER;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.LOGINCONTEXT_KEY;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.LOGIN_ERROR;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.LOGOUT_PAGE;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.PAGE_AFTER_SWITCH;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.REQUESTED_URL;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.SECURITY_ERROR;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.SSO_INITIAL_URL_REQUEST_KEY;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.START_PAGE_SAVE_KEY;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.SWITCH_USER_KEY;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.SWITCH_USER_PAGE;
import static org.nuxeo.ecm.platform.ui.web.auth.NXAuthConstants.USERIDENT_KEY;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.URIUtils;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.SimplePrincipal;
import org.nuxeo.ecm.core.api.local.ClientLoginModule;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventProducer;
import org.nuxeo.ecm.core.event.impl.UnboundEventContext;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.ecm.platform.api.login.UserIdentificationInfo;
import org.nuxeo.ecm.platform.api.login.UserIdentificationInfoCallbackHandler;
import org.nuxeo.ecm.platform.login.PrincipalImpl;
import org.nuxeo.ecm.platform.login.TrustingLoginPlugin;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.LoginResponseHandler;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthPreFilter;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPlugin;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPluginLogoutExtension;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPropagator;
import org.nuxeo.ecm.platform.ui.web.auth.service.AuthenticationPluginDescriptor;
import org.nuxeo.ecm.platform.ui.web.auth.service.NuxeoAuthFilterChain;
import org.nuxeo.ecm.platform.ui.web.auth.service.OpenUrlDescriptor;
import org.nuxeo.ecm.platform.ui.web.auth.service.PluggableAuthenticationService;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.platform.web.common.session.NuxeoHttpSessionMonitor;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.metrics.MetricsService;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;

/**
 * Servlet filter handling Nuxeo authentication (JAAS + EJB).
 * <p>
 * Also handles logout and identity switch.
 *
 * @author Thierry Delprat
 * @author Bogdan Stefanescu
 * @author Anahide Tchertchian
 * @author Florent Guillaume
 */
public class NuxeoAuthenticationFilter implements Filter {

    private static final Log log = LogFactory.getLog(NuxeoAuthenticationFilter.class);

    // protected static final String EJB_LOGIN_DOMAIN = "nuxeo-system-login";

    public static final String DEFAULT_START_PAGE = "nxstartup.faces";

    /**
     * LoginContext domain name in use by default in Nuxeo.
     */
    public static final String LOGIN_DOMAIN = "nuxeo-ecm-web";

    protected static final String XMLHTTP_REQUEST_TYPE = "XMLHttpRequest";

    protected static final String LOGIN_JMS_CATEGORY = "NuxeoAuthentication";

    public static final String IS_LOGIN_NOT_SYNCHRONIZED_PROPERTY_KEY = "org.nuxeo.ecm.platform.ui.web.auth.NuxeoAuthenticationFilter.isLoginNotSynchronized";

    /** Used internally as a marker. */
    protected static final Principal DIRECTORY_ERROR_PRINCIPAL = new PrincipalImpl(
            "__DIRECTORY_ERROR__\0\0\0");

    private static String anonymous;

    protected final boolean avoidReauthenticate = true;

    protected PluggableAuthenticationService service;

    protected ReentrantReadWriteLock unAuthenticatedURLPrefixLock = new ReentrantReadWriteLock();

    protected List<String> unAuthenticatedURLPrefix;

    /**
     * On WebEngine (Jetty) we don't have JMS enabled so we should disable log
     */
    protected boolean byPassAuthenticationLog = false;

    /**
     * Which security domain to use
     */
    protected String securityDomain = LOGIN_DOMAIN;

    // @since 5.7
    protected final MetricRegistry registry = SharedMetricRegistries.getOrCreate(MetricsService.class.getName());

    protected final Timer requestTimer = registry.timer(MetricRegistry.name(
            "nuxeo", "web", "authentication", "requests", "count"));

    protected final Counter concurrentCount = registry.counter(MetricRegistry.name(
            "nuxeo", "web", "authentication", "requests", "concurrent", "count"));

    protected final Counter concurrentMaxCount = registry.counter(MetricRegistry.name(
            "nuxeo", "web", "authentication", "requests", "concurrent", "max"));

    protected final Counter loginCount = registry.counter(MetricRegistry.name(
            "nuxeo", "web", "authentication", "logged-users"));

    @Override
    public void destroy() {
    }

    protected static boolean sendAuthenticationEvent(
            UserIdentificationInfo userInfo, String eventId, String comment) {

        LoginContext loginContext = null;
        try {
            try {
                loginContext = Framework.login();
            } catch (LoginException e) {
                log.error("Unable to log in in order to log Login event"
                        + e.getMessage());
                return false;
            }

            EventProducer evtProducer = null;
            try {
                evtProducer = Framework.getService(EventProducer.class);
            } catch (Exception e) {
                log.error("Unable to get Event producer: " + e.getMessage());
                return false;
            }

            Principal principal = new SimplePrincipal(userInfo.getUserName());

            Map<String, Serializable> props = new HashMap<String, Serializable>();
            props.put("AuthenticationPlugin", userInfo.getAuthPluginName());
            props.put("LoginPlugin", userInfo.getLoginPluginName());
            props.put("category", LOGIN_JMS_CATEGORY);
            props.put("comment", comment);

            EventContext ctx = new UnboundEventContext(principal, props);
            try {
                evtProducer.fireEvent(ctx.newEvent(eventId));
            } catch (ClientException e) {
                log.error("Unable to send authentication event", e);
            }
            return true;
        } finally {
            if (loginContext != null) {
                try {
                    loginContext.logout();
                } catch (LoginException e) {
                    log.error("Unable to logout: " + e.getMessage());
                }
            }
        }
    }

    protected boolean logAuthenticationAttempt(UserIdentificationInfo userInfo,
            boolean success) {
        if (byPassAuthenticationLog) {
            return true;
        }
        String userName = userInfo.getUserName();
        if (userName == null || userName.length() == 0) {
            userName = userInfo.getToken();
        }

        String eventId;
        String comment;
        if (success) {
            eventId = "loginSuccess";
            comment = userName + " successfully logged in using "
                    + userInfo.getAuthPluginName() + "Authentication";
            loginCount.inc();
        } else {
            eventId = "loginFailed";
            comment = userName + " failed to authenticate using "
                    + userInfo.getAuthPluginName() + "Authentication";
        }

        return sendAuthenticationEvent(userInfo, eventId, comment);
    }

    protected boolean logLogout(UserIdentificationInfo userInfo) {
        if (byPassAuthenticationLog) {
            return true;
        }
        loginCount.dec();
        String userName = userInfo.getUserName();
        if (userName == null || userName.length() == 0) {
            userName = userInfo.getToken();
        }

        String eventId = "logout";
        String comment = userName + " logged out";

        return sendAuthenticationEvent(userInfo, eventId, comment);
    }

    protected Principal doAuthenticate(
            CachableUserIdentificationInfo cachableUserIdent,
            HttpServletRequest httpRequest) {

        LoginContext loginContext;
        try {
            CallbackHandler handler = service.getCallbackHandler(cachableUserIdent.getUserInfo());
            loginContext = new LoginContext(securityDomain, handler);

            if (Boolean.parseBoolean(Framework.getProperty(IS_LOGIN_NOT_SYNCHRONIZED_PROPERTY_KEY))) {
                loginContext.login();
            } else {
                synchronized (NuxeoAuthenticationFilter.class) {
                    loginContext.login();
                }
            }

            Principal principal = (Principal) loginContext.getSubject().getPrincipals().toArray()[0];
            cachableUserIdent.setPrincipal(principal);
            cachableUserIdent.setAlreadyAuthenticated(true);
            // re-set the userName since for some SSO based on token,
            // the userName is not known before login is completed
            cachableUserIdent.getUserInfo().setUserName(principal.getName());

            logAuthenticationAttempt(cachableUserIdent.getUserInfo(), true);
        } catch (LoginException e) {
            log.info("Login failed for "
                    + cachableUserIdent.getUserInfo().getUserName());
            logAuthenticationAttempt(cachableUserIdent.getUserInfo(), false);
            if (e.getCause() instanceof DirectoryException) {
                return DIRECTORY_ERROR_PRINCIPAL;
            }
            return null;
        }

        // store login context for the time of the request
        // TODO logincontext is also stored in cachableUserIdent - it is really
        // needed to store it??
        httpRequest.setAttribute(LOGINCONTEXT_KEY, loginContext);

        // store user ident
        cachableUserIdent.setLoginContext(loginContext);
        boolean createSession = needSessionSaving(cachableUserIdent.getUserInfo());
        HttpSession session = httpRequest.getSession(createSession);
        if (session != null) {
            session.setAttribute(USERIDENT_KEY, cachableUserIdent);
        }

        service.onAuthenticatedSessionCreated(httpRequest, session,
                cachableUserIdent);

        return cachableUserIdent.getPrincipal();
    }

    private boolean switchUser(ServletRequest request,
            ServletResponse response, FilterChain chain) throws IOException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        String deputyLogin = (String) httpRequest.getAttribute(SWITCH_USER_KEY);
        String targetPageAfterSwitch = (String) httpRequest.getAttribute(PAGE_AFTER_SWITCH);
        if (targetPageAfterSwitch == null) {
            targetPageAfterSwitch = DEFAULT_START_PAGE;
        }

        CachableUserIdentificationInfo cachableUserIdent = retrieveIdentityFromCache(httpRequest);
        String originatingUser = cachableUserIdent.getUserInfo().getUserName();

        if (deputyLogin == null) {
            // simply switch back to the previous identity
            NuxeoPrincipal currentPrincipal = (NuxeoPrincipal) cachableUserIdent.getPrincipal();
            String previousUser = currentPrincipal.getOriginatingUser();
            if (previousUser == null) {
                return false;
            }
            deputyLogin = previousUser;
            originatingUser = null;
        }

        try {
            cachableUserIdent.getLoginContext().logout();
        } catch (LoginException e1) {
            log.error("Error while logout from main identity", e1);
        }

        httpRequest.getSession(false);
        service.reinitSession(httpRequest);

        CachableUserIdentificationInfo newCachableUserIdent = new CachableUserIdentificationInfo(
                deputyLogin, deputyLogin);

        newCachableUserIdent.getUserInfo().setLoginPluginName(
                TrustingLoginPlugin.NAME);
        newCachableUserIdent.getUserInfo().setAuthPluginName(
                cachableUserIdent.getUserInfo().getAuthPluginName());

        Principal principal = doAuthenticate(newCachableUserIdent, httpRequest);
        if (principal != null && principal != DIRECTORY_ERROR_PRINCIPAL) {
            NuxeoPrincipal nxUser = (NuxeoPrincipal) principal;
            if (originatingUser != null) {
                nxUser.setOriginatingUser(originatingUser);
            }
            propagateUserIdentificationInformation(cachableUserIdent);
        }

        // reinit Seam so the afterResponseComplete does not crash
        // ServletLifecycle.beginRequest(httpRequest);

        // flag redirect to avoid being caught by URLPolicy
        request.setAttribute(DISABLE_REDIRECT_REQUEST_KEY, Boolean.TRUE);
        String baseURL = service.getBaseURL(request);
        ((HttpServletResponse) response).sendRedirect(baseURL
                + targetPageAfterSwitch);

        return true;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        final Timer.Context contextTimer = requestTimer.time();
        concurrentCount.inc();
        if (concurrentCount.getCount() > concurrentMaxCount.getCount()) {
            concurrentMaxCount.inc();
        }
        try {
            doInitIfNeeded();

            List<NuxeoAuthPreFilter> preFilters = service.getPreFilters();

            if (preFilters == null) {
                doFilterInternal(request, response, chain);
            } else {
                NuxeoAuthFilterChain chainWithPreFilters = new NuxeoAuthFilterChain(
                        preFilters, chain, this);
                chainWithPreFilters.doFilter(request, response);
            }
        } finally {
            ClientLoginModule.clearThreadLocalLogin();
            contextTimer.stop();
            concurrentCount.dec();
        }
    }

    public void doFilterInternal(ServletRequest request,
            ServletResponse response, FilterChain chain) throws IOException,
            ServletException {

        if (bypassAuth((HttpServletRequest) request)) {
            chain.doFilter(request, response);
            return;
        }

        String tokenPage = getRequestedPage(request);
        if (tokenPage.equals(SWITCH_USER_PAGE)) {
            boolean result = switchUser(request, response, chain);
            if (result) {
                return;
            }
        }

        if (request instanceof NuxeoSecuredRequestWrapper) {
            log.debug("ReEntering Nuxeo Authentication Filter ... exiting directly");
            chain.doFilter(request, response);
            return;
        } else if (service.canBypassRequest(request)) {
            log.debug("ReEntering Nuxeo Authentication Filter after URL rewrite ... exiting directly");
            chain.doFilter(request, response);
            return;
        } else {
            log.debug("Entering Nuxeo Authentication Filter");
        }

        String targetPageURL = null;
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        Principal principal = httpRequest.getUserPrincipal();

        NuxeoAuthenticationPropagator.CleanupCallback propagatedAuthCb = null;

        try {
            if (principal == null) {
                log.debug("Principal not found inside Request via getUserPrincipal");
                // need to authenticate !

                // retrieve user & password
                CachableUserIdentificationInfo cachableUserIdent;
                if (avoidReauthenticate) {
                    log.debug("Try getting authentication from cache");
                    cachableUserIdent = retrieveIdentityFromCache(httpRequest);
                } else {
                    log.debug("Principal cache is NOT activated");
                }

                if (cachableUserIdent != null
                        && cachableUserIdent.getUserInfo() != null
                        && service.needResetLogin(request)) {
                    HttpSession session = httpRequest.getSession(false);
                    if (session != null) {
                        session.removeAttribute(USERIDENT_KEY);
                    }
                    // first propagate the login because invalidation may
                    // require
                    // an authenticated session
                    propagatedAuthCb = service.propagateUserIdentificationInformation(cachableUserIdent);
                    // invalidate Session !
                    try {
                        service.invalidateSession(request);
                    } finally {
                        if (propagatedAuthCb != null) {
                            propagatedAuthCb.cleanup();
                            propagatedAuthCb = null;
                        }
                    }
                    // TODO perform logout?
                    cachableUserIdent = null;
                }

                // identity found in cache
                if (cachableUserIdent != null
                        && cachableUserIdent.getUserInfo() != null) {
                    log.debug("userIdent found in cache, get the Principal from it without reloggin");

                    NuxeoHttpSessionMonitor.instance().updateEntry(httpRequest);

                    principal = cachableUserIdent.getPrincipal();
                    log.debug("Principal = " + principal.getName());
                    propagatedAuthCb = service.propagateUserIdentificationInformation(cachableUserIdent);

                    String requestedPage = getRequestedPage(httpRequest);
                    if (requestedPage.equals(LOGOUT_PAGE)) {
                        boolean redirected = handleLogout(request, response,
                                cachableUserIdent);
                        cachableUserIdent = null;
                        principal = null;
                        if (redirected
                                && httpRequest.getParameter(FORM_SUBMITTED_MARKER) == null) {
                            return;
                        }
                    } else {
                        targetPageURL = getSavedRequestedURL(httpRequest,
                                httpResponse);
                    }
                }

                // identity not found in cache or reseted by logout
                if (cachableUserIdent == null
                        || cachableUserIdent.getUserInfo() == null) {
                    UserIdentificationInfo userIdent = handleRetrieveIdentity(
                            httpRequest, httpResponse);
                    if (userIdent != null
                            && userIdent.getUserName().equals(getAnonymousId())) {
                        String forceAuth = httpRequest.getParameter(FORCE_ANONYMOUS_LOGIN);
                        if (forceAuth != null && forceAuth.equals("true")) {
                            userIdent = null;
                        }
                    }
                    if ((userIdent == null || !userIdent.containsValidIdentity())
                            && !bypassAuth(httpRequest)) {

                        boolean res = handleLoginPrompt(httpRequest,
                                httpResponse);
                        if (res) {
                            return;
                        }
                    } else {
                        // restore saved Starting page
                        targetPageURL = getSavedRequestedURL(httpRequest,
                                httpResponse);
                    }

                    if (userIdent != null && userIdent.containsValidIdentity()) {
                        // do the authentication
                        cachableUserIdent = new CachableUserIdentificationInfo(
                                userIdent);
                        principal = doAuthenticate(cachableUserIdent,
                                httpRequest);
                        if (principal != null
                                && principal != DIRECTORY_ERROR_PRINCIPAL) {
                            // Do the propagation too ????
                            propagatedAuthCb = service.propagateUserIdentificationInformation(cachableUserIdent);
                            // setPrincipalToSession(httpRequest, principal);
                            // check if the current authenticator is a
                            // LoginResponseHandler
                            NuxeoAuthenticationPlugin plugin = getAuthenticator(cachableUserIdent);
                            if (plugin instanceof LoginResponseHandler) {
                                // call the extended error handler
                                if (((LoginResponseHandler) plugin).onSuccess(
                                        (HttpServletRequest) request,
                                        (HttpServletResponse) response)) {
                                    return;
                                }
                            }
                        } else {
                            // first check if the current authenticator is a
                            // LoginResponseHandler
                            NuxeoAuthenticationPlugin plugin = getAuthenticator(cachableUserIdent);
                            if (plugin instanceof LoginResponseHandler) {
                                // call the extended error handler
                                if (((LoginResponseHandler) plugin).onError(
                                        (HttpServletRequest) request,
                                        (HttpServletResponse) response)) {
                                    return;
                                }
                            } else {
                                // use the old method
                                String err = principal == DIRECTORY_ERROR_PRINCIPAL ? ERROR_CONNECTION_FAILED
                                        : ERROR_AUTHENTICATION_FAILED;
                                httpRequest.setAttribute(LOGIN_ERROR, err);
                                boolean res = handleLoginPrompt(httpRequest,
                                        httpResponse);
                                if (res) {
                                    return;
                                }
                            }
                        }

                    }
                }
            }

            if (principal != null) {
                if (targetPageURL != null && targetPageURL.length() > 0) {
                    // forward to target page
                    String baseURL = service.getBaseURL(request);

                    // httpRequest.getRequestDispatcher(targetPageURL).forward(new
                    // NuxeoSecuredRequestWrapper(httpRequest, principal),
                    // response);
                    if (XMLHTTP_REQUEST_TYPE.equalsIgnoreCase(httpRequest.getHeader("X-Requested-With"))) {
                        // httpResponse.setStatus(200);
                        return;
                    } else {
                        httpResponse.sendRedirect(baseURL + targetPageURL);
                        return;
                    }

                } else {
                    // simply continue request
                    chain.doFilter(new NuxeoSecuredRequestWrapper(httpRequest,
                            principal), response);
                }
            } else {
                chain.doFilter(request, response);
            }
        } finally {
            if (propagatedAuthCb != null) {
                propagatedAuthCb.cleanup();
            }
        }
        if (!avoidReauthenticate) {
            // destroy login context
            log.debug("Log out");
            LoginContext lc = (LoginContext) httpRequest.getAttribute("LoginContext");
            if (lc != null) {
                try {
                    lc.logout();
                } catch (LoginException e) {
                    log.error(e, e);
                }
            }
        }
        log.debug("Exit Nuxeo Authentication filter");
    }

    public NuxeoAuthenticationPlugin getAuthenticator(
            CachableUserIdentificationInfo ci) {
        String key = ci.getUserInfo().getAuthPluginName();
        if (key != null) {
            NuxeoAuthenticationPlugin authPlugin = service.getPlugin(key);
            return authPlugin;
        }
        return null;
    }

    protected static CachableUserIdentificationInfo retrieveIdentityFromCache(
            HttpServletRequest httpRequest) {

        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            CachableUserIdentificationInfo cachableUserInfo = (CachableUserIdentificationInfo) session.getAttribute(USERIDENT_KEY);
            if (cachableUserInfo != null) {
                return cachableUserInfo;
            }
        }

        return null;
    }

    private String getAnonymousId() throws ServletException {
        if (anonymous == null) {
            try {
                UserManager um = Framework.getService(UserManager.class);
                anonymous = um.getAnonymousUserId();
            } catch (Exception e) {
                log.error("Can't find anonymous User id", e);
                anonymous = "";
                throw new ServletException("Can't find anonymous user id");
            }
        }
        return anonymous;
    }

    protected void doInitIfNeeded() throws ServletException {
        if (service == null && Framework.getRuntime() != null) {
            service = (PluggableAuthenticationService) Framework.getRuntime().getComponent(
                    PluggableAuthenticationService.NAME);
            // init preFilters
            service.initPreFilters();
            if (service == null) {
                log.error("Unable to get Service "
                        + PluggableAuthenticationService.NAME);
                throw new ServletException(
                        "Can't initialize Nuxeo Pluggable Authentication Service");
            }
        }
    }

    @Override
    public void init(FilterConfig config) throws ServletException {
        String val = config.getInitParameter("byPassAuthenticationLog");
        if (val != null && Boolean.parseBoolean(val)) {
            byPassAuthenticationLog = true;
        }
        val = config.getInitParameter("securityDomain");
        if (val != null) {
            securityDomain = val;
        }

    }

    /**
     * Save requested URL before redirecting to login form.
     * <p>
     * Returns true if target url is a valid startup page.
     */
    public boolean saveRequestedURLBeforeRedirect(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        HttpSession session;
        if (httpResponse.isCommitted()) {
            session = httpRequest.getSession(false);
        } else {
            session = httpRequest.getSession(true);
        }

        if (session == null) {
            return false;
        }

        String requestPage;
        boolean requestPageInParams = false;
        if (httpRequest.getParameter(REQUESTED_URL) != null) {
            requestPageInParams = true;
            requestPage = httpRequest.getParameter(REQUESTED_URL);
        } else {
            requestPage = getRequestedUrl(httpRequest);
        }

        if (requestPage == null) {
            return false;
        }

        // avoid redirect if not useful
        if (requestPage.startsWith(DEFAULT_START_PAGE)) {
            return true;
        }

        // avoid saving to session is start page is not valid or if it's
        // already in the request params
        if (isStartPageValid(requestPage)) {
            if (!requestPageInParams) {
                session.setAttribute(START_PAGE_SAVE_KEY, requestPage);
            }
            return true;
        }

        return false;
    }

    public static String getRequestedUrl(HttpServletRequest httpRequest) {
        String completeURI = httpRequest.getRequestURI();
        String qs = httpRequest.getQueryString();
        String context = httpRequest.getContextPath() + '/';
        String requestPage = completeURI.substring(context.length());
        if (qs != null && qs.length() > 0) {
            // remove conversationId if present
            if (qs.contains("conversationId")) {
                qs = qs.replace("conversationId", "old_conversationId");
            }
            requestPage = requestPage + '?' + qs;
        }
        return requestPage;
    }

    protected static String getSavedRequestedURL(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        String requestedPage = null;
        if (httpRequest.getParameter(REQUESTED_URL) != null) {
            String requestedUrl = httpRequest.getParameter(REQUESTED_URL);
            if (requestedUrl != null && !"".equals(requestedUrl)) {
                try {
                    requestedPage = URLDecoder.decode(requestedUrl, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    log.error("Unable to get the requestedUrl parameter" + e);
                }
            }
        } else {
            // retrieve from session
            HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                requestedPage = (String) session.getAttribute(START_PAGE_SAVE_KEY);
                if (requestedPage != null) {
                    // clean up session
                    session.removeAttribute(START_PAGE_SAVE_KEY);
                }
            }

            // retrieve from SSO cookies
            Cookie[] cookies = httpRequest.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (SSO_INITIAL_URL_REQUEST_KEY.equals(cookie.getName())) {
                        requestedPage = cookie.getValue();
                        cookie.setPath("/");
                        // enforce cookie removal
                        cookie.setMaxAge(0);
                        httpResponse.addCookie(cookie);
                    }
                }
            }
        }

        // add locale if not in the URL params
        String localeStr = httpRequest.getParameter(NXAuthConstants.LANGUAGE_PARAMETER);
        if (requestedPage != null && !"".equals(requestedPage)
                && localeStr != null) {
            Map<String, String> params = new HashMap<String, String>();
            if (!URIUtils.getRequestParameters(requestedPage).containsKey(
                    NXAuthConstants.LANGUAGE_PARAMETER)) {
                params.put(NXAuthConstants.LANGUAGE_PARAMETER, localeStr);
            }
            return URIUtils.addParametersToURIQuery(requestedPage, params);
        }

        return requestedPage;
    }

    protected boolean isStartPageValid(String startPage) {
        if (startPage == null) {
            return false;
        }
        try {
            // Sometimes, the service is not initialized at startup
            doInitIfNeeded();
        } catch (ServletException e) {
            return false;
        }
        for (String prefix : service.getStartURLPatterns()) {
            if (startPage.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    protected boolean handleLogout(ServletRequest request,
            ServletResponse response,
            CachableUserIdentificationInfo cachedUserInfo)
            throws ServletException {
        logLogout(cachedUserInfo.getUserInfo());

        // invalidate Session !
        service.invalidateSession(request);

        request.setAttribute(DISABLE_REDIRECT_REQUEST_KEY, Boolean.TRUE);
        Map<String, String> parameters = new HashMap<String, String>();
        String securityError = request.getParameter(SECURITY_ERROR);
        if (securityError != null) {
            parameters.put(SECURITY_ERROR, securityError);
        }
        if (cachedUserInfo.getPrincipal().getName().equals(getAnonymousId())) {
            parameters.put(FORCE_ANONYMOUS_LOGIN, "true");
        }
        String requestedUrl = request.getParameter(REQUESTED_URL);
        if (requestedUrl != null) {
            parameters.put(REQUESTED_URL, requestedUrl);
        }
        // Reset JSESSIONID Cookie
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        Cookie cookie = new Cookie("JSESSIONID", null);
        cookie.setMaxAge(0);
        cookie.setPath("/");
        httpResponse.addCookie(cookie);

        String pluginName = cachedUserInfo.getUserInfo().getAuthPluginName();
        NuxeoAuthenticationPlugin authPlugin = service.getPlugin(pluginName);
        NuxeoAuthenticationPluginLogoutExtension logoutPlugin = null;

        if (authPlugin instanceof NuxeoAuthenticationPluginLogoutExtension) {
            logoutPlugin = (NuxeoAuthenticationPluginLogoutExtension) authPlugin;
        }

        boolean redirected = false;
        if (logoutPlugin != null) {
            redirected = Boolean.TRUE.equals(logoutPlugin.handleLogout(
                    (HttpServletRequest) request,
                    (HttpServletResponse) response));
        }
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if (!redirected
                && !XMLHTTP_REQUEST_TYPE.equalsIgnoreCase(httpRequest.getHeader("X-Requested-With"))) {
            String baseURL = service.getBaseURL(request);
            try {
                String url = baseURL + DEFAULT_START_PAGE;
                url = URIUtils.addParametersToURIQuery(url, parameters);
                ((HttpServletResponse) response).sendRedirect(url);
                redirected = true;
            } catch (IOException e) {
                log.error("Unable to redirect to default start page after logout : "
                        + e.getMessage());
            }
        }

        try {
            cachedUserInfo.getLoginContext().logout();
        } catch (LoginException e) {
            log.error("Unable to logout " + e.getMessage());
        }
        return redirected;
    }

    // App Server JAAS SPI
    protected void propagateUserIdentificationInformation(
            CachableUserIdentificationInfo cachableUserIdent) {
        service.propagateUserIdentificationInformation(cachableUserIdent);
    }

    // Plugin API
    protected void initUnAuthenticatedURLPrefix() {
        // gather unAuthenticated URLs
        unAuthenticatedURLPrefix = new ArrayList<String>();
        for (String pluginName : service.getAuthChain()) {
            NuxeoAuthenticationPlugin plugin = service.getPlugin(pluginName);
            List<String> prefix = plugin.getUnAuthenticatedURLPrefix();
            if (prefix != null && !prefix.isEmpty()) {
                unAuthenticatedURLPrefix.addAll(prefix);
            }
        }
    }

    protected boolean bypassAuth(HttpServletRequest httpRequest) {
        if (unAuthenticatedURLPrefix == null) {
            try {
                unAuthenticatedURLPrefixLock.writeLock().lock();
                // late init to allow plugins registered after this filter init
                initUnAuthenticatedURLPrefix();
            } finally {
                unAuthenticatedURLPrefixLock.writeLock().unlock();
            }
        }

        try {
            unAuthenticatedURLPrefixLock.readLock().lock();
            String requestPage = getRequestedPage(httpRequest);
            for (String prefix : unAuthenticatedURLPrefix) {
                if (requestPage.startsWith(prefix)) {
                    return true;
                }
            }
        } finally {
            unAuthenticatedURLPrefixLock.readLock().unlock();
        }

        List<OpenUrlDescriptor> openUrls = service.getOpenUrls();
        for (OpenUrlDescriptor openUrl : openUrls) {
            if (openUrl.allowByPassAuth(httpRequest)) {
                return true;
            }
        }

        return false;
    }

    public static String getRequestedPage(ServletRequest request) {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            return getRequestedPage(httpRequest);
        } else {
            return null;
        }
    }

    protected static String getRequestedPage(HttpServletRequest httpRequest) {
        String requestURI = httpRequest.getRequestURI();
        String context = httpRequest.getContextPath() + '/';

        return requestURI.substring(context.length());
    }

    protected boolean handleLoginPrompt(HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String baseURL = service.getBaseURL(httpRequest);

        // go through plugins to get UserIndentity
        for (String pluginName : service.getAuthChain(httpRequest)) {
            NuxeoAuthenticationPlugin plugin = service.getPlugin(pluginName);
            AuthenticationPluginDescriptor descriptor = service.getDescriptor(pluginName);

            if (Boolean.TRUE.equals(plugin.needLoginPrompt(httpRequest))) {
                if (descriptor.getNeedStartingURLSaving()) {
                    saveRequestedURLBeforeRedirect(httpRequest, httpResponse);
                }
                return Boolean.TRUE.equals(plugin.handleLoginPrompt(
                        httpRequest, httpResponse, baseURL));
            }
        }

        log.warn("No auth plugin can be found to do the Login Prompt");
        return false;
    }

    protected UserIdentificationInfo handleRetrieveIdentity(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        UserIdentificationInfo userIdent = null;

        // go through plugins to get UserIdentity
        for (String pluginName : service.getAuthChain(httpRequest)) {
            NuxeoAuthenticationPlugin plugin = service.getPlugin(pluginName);
            if (plugin != null) {
                log.debug("Trying to retrieve userIdentification using plugin "
                        + pluginName);
                userIdent = plugin.handleRetrieveIdentity(httpRequest,
                        httpResponse);
                if (userIdent != null && userIdent.containsValidIdentity()) {
                    // fill information for the Login module
                    userIdent.setAuthPluginName(pluginName);

                    // get the target login module
                    String loginModulePlugin = service.getDescriptor(pluginName).getLoginModulePlugin();
                    userIdent.setLoginPluginName(loginModulePlugin);

                    // get the additional parameters
                    Map<String, String> parameters = service.getDescriptor(
                            pluginName).getParameters();
                    if (userIdent.getLoginParameters() != null) {
                        // keep existing parameters set by the auth plugin
                        if (parameters == null) {
                            parameters = new HashMap<String, String>();
                        }
                        parameters.putAll(userIdent.getLoginParameters());
                    }
                    userIdent.setLoginParameters(parameters);

                    break;
                }
            } else {
                log.error("Auth plugin " + pluginName
                        + " can not be retrieved from service");
            }
        }

        // Fall back to cache (used only when avoidReautenticated=false)
        if (userIdent == null || !userIdent.containsValidIdentity()) {
            log.debug("user/password not found in request, try into identity cache");
            HttpSession session = httpRequest.getSession(false);
            if (session == null) {
                // possible we need a new session
                if (httpRequest.isRequestedSessionIdValid()) {
                    session = httpRequest.getSession(true);
                }
            }
            if (session != null) {
                CachableUserIdentificationInfo savedUserInfo = retrieveIdentityFromCache(httpRequest);
                if (savedUserInfo != null) {
                    log.debug("Found User identity in cache :"
                            + savedUserInfo.getUserInfo().getUserName() + '/'
                            + savedUserInfo.getUserInfo().getPassword());
                    userIdent = new UserIdentificationInfo(
                            savedUserInfo.getUserInfo());
                    savedUserInfo.setPrincipal(null);
                }
            }
        } else {
            log.debug("User/Password found as parameter of the request");
        }

        return userIdent;
    }

    protected boolean needSessionSaving(UserIdentificationInfo userInfo) {
        String pluginName = userInfo.getAuthPluginName();

        AuthenticationPluginDescriptor desc = service.getDescriptor(pluginName);

        if (desc.getStateful()) {
            return true;
        } else {
            return desc.getNeedStartingURLSaving();
        }
    }

    /**
     * Does a forced login as the given user. Bypasses all authentication
     * checks.
     *
     * @param username the user name
     * @return the login context, which MUST be used for logout in a
     *         {@code finally} block
     * @throws LoginException
     */
    public static LoginContext loginAs(String username) throws LoginException {
        UserIdentificationInfo userIdent = new UserIdentificationInfo(username,
                "");
        userIdent.setLoginPluginName(TrustingLoginPlugin.NAME);
        PluggableAuthenticationService authService = (PluggableAuthenticationService) Framework.getRuntime().getComponent(
                PluggableAuthenticationService.NAME);
        CallbackHandler callbackHandler;
        if (authService != null) {
            callbackHandler = authService.getCallbackHandler(userIdent);
        } else {
            callbackHandler = new UserIdentificationInfoCallbackHandler(
                    userIdent);
        }
        LoginContext loginContext = new LoginContext(LOGIN_DOMAIN,
                callbackHandler);

        if (Boolean.parseBoolean(Framework.getProperty(IS_LOGIN_NOT_SYNCHRONIZED_PROPERTY_KEY))) {
            loginContext.login();
        } else {
            synchronized (NuxeoAuthenticationFilter.class) {
                loginContext.login();
            }
        }
        return loginContext;
    }

}
