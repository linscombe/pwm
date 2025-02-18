/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2021 The PWM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package password.pwm.http.servlet;

import com.novell.ldapchai.exception.ChaiUnavailableException;
import password.pwm.PwmConstants;
import password.pwm.PwmDomain;
import password.pwm.bean.UserIdentity;
import password.pwm.config.PwmSetting;
import password.pwm.config.value.data.ShortcutItem;
import password.pwm.config.value.data.UserPermission;
import password.pwm.error.PwmError;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.HttpMethod;
import password.pwm.http.JspUrl;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmRequestAttribute;
import password.pwm.http.PwmSession;
import password.pwm.http.bean.ShortcutsBean;
import password.pwm.ldap.permission.UserPermissionType;
import password.pwm.ldap.permission.UserPermissionUtility;
import password.pwm.svc.stats.Statistic;
import password.pwm.svc.stats.StatisticsClient;
import password.pwm.util.java.JavaHelper;
import password.pwm.util.java.MiscUtil;
import password.pwm.util.java.StringUtil;
import password.pwm.util.logging.PwmLogger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@WebServlet(
        name = "ShortcutServlet",
        urlPatterns = {
                PwmConstants.URL_PREFIX_PRIVATE + "/shortcuts",
                PwmConstants.URL_PREFIX_PRIVATE + "/Shortcuts",
        }
)
public class ShortcutServlet extends AbstractPwmServlet
{

    private static final PwmLogger LOGGER = PwmLogger.forClass( ShortcutServlet.class );

    public enum ShortcutAction implements AbstractPwmServlet.ProcessAction
    {
        selectShortcut,;

        @Override
        public Collection<HttpMethod> permittedMethods( )
        {
            return Collections.singletonList( HttpMethod.GET );
        }
    }

    @Override
    protected Optional<ShortcutAction> readProcessAction( final PwmRequest request )
            throws PwmUnrecoverableException
    {
        return JavaHelper.readEnumFromString( ShortcutAction.class, request.readParameterAsString( PwmConstants.PARAM_ACTION_REQUEST ) );
    }

    @Override
    protected void processAction( final PwmRequest pwmRequest )
            throws ServletException, IOException, ChaiUnavailableException, PwmUnrecoverableException
    {
        final PwmDomain pwmDomain = pwmRequest.getPwmDomain();

        if ( !pwmDomain.getConfig().readSettingAsBoolean( PwmSetting.SHORTCUT_ENABLE ) )
        {
            pwmRequest.respondWithError( PwmError.ERROR_SERVICE_NOT_AVAILABLE.toInfo() );
            return;
        }

        final ShortcutsBean shortcutsBean = pwmDomain.getSessionStateService().getBean( pwmRequest, ShortcutsBean.class );
        if ( shortcutsBean.getVisibleItems() == null )
        {
            LOGGER.debug( pwmRequest, () -> "building visible shortcut list for user" );
            final Map<String, ShortcutItem> visibleItems = figureVisibleShortcuts( pwmRequest );
            shortcutsBean.setVisibleItems( visibleItems );
        }
        else
        {
            LOGGER.trace( pwmRequest, () -> "using cashed shortcut values" );
        }

        final Optional<ShortcutAction> action = readProcessAction( pwmRequest );
        if ( action.isPresent() )
        {
            pwmRequest.validatePwmFormID();
            switch ( action.get() )
            {
                case selectShortcut:
                    handleUserSelection( pwmRequest, shortcutsBean );
                    return;

                default:
                    MiscUtil.unhandledSwitchStatement( action.get() );
            }
        }

        forwardToJsp( pwmRequest, shortcutsBean );
    }

    private void forwardToJsp( final PwmRequest pwmRequest, final ShortcutsBean shortcutsBean )
            throws ServletException, PwmUnrecoverableException, IOException
    {
        final ArrayList<ShortcutItem> shortcutItems = new ArrayList<>( shortcutsBean.getVisibleItems().values() );
        pwmRequest.setAttribute( PwmRequestAttribute.ShortcutItems, shortcutItems );
        pwmRequest.forwardToJsp( JspUrl.SHORTCUT );
    }

    /**
     * Loop through each configured shortcut setting to determine if the shortcut is is able to the user pwmSession.
     */
    private static Map<String, ShortcutItem> figureVisibleShortcuts(
            final PwmRequest pwmRequest
    )
            throws PwmUnrecoverableException
    {
        final Collection<String> configValues = pwmRequest.getDomainConfig().readSettingAsLocalizedStringArray( PwmSetting.SHORTCUT_ITEMS, pwmRequest.getLocale() );

        final Set<String> labelsFromHeader = new HashSet<>();
        {
            final Map<String, List<String>> headerValueMap = pwmRequest.readHeaderValuesMap();
            final List<String> interestedHeaderNames = pwmRequest.getDomainConfig().readSettingAsStringArray( PwmSetting.SHORTCUT_HEADER_NAMES );

            for ( final Map.Entry<String, List<String>> entry : headerValueMap.entrySet() )
            {
                final String headerName = entry.getKey();
                if ( interestedHeaderNames.contains( headerName ) )
                {
                    for ( final String loopValues : entry.getValue() )
                    {
                        labelsFromHeader.addAll( StringUtil.tokenizeString( loopValues, "," ) );
                    }
                }
            }
        }

        final List<ShortcutItem> configuredItems = configValues.stream()
                .map( ShortcutItem::parsePwmConfigInput )
                .collect( Collectors.toUnmodifiableList() );

        final Map<String, ShortcutItem> visibleItems = new LinkedHashMap<>( configuredItems.size() );

        if ( !labelsFromHeader.isEmpty() )
        {
            LOGGER.trace( () -> "detected the following labels from headers: " + StringUtil.collectionToString( labelsFromHeader, "," ) );
            visibleItems.keySet().retainAll( labelsFromHeader );
        }
        else
        {
            for ( final ShortcutItem item : configuredItems )
            {
                final UserIdentity userIdentity = pwmRequest.getPwmSession().getUserInfo().getUserIdentity();

                final UserPermission userPermission = UserPermission.builder()
                        .type( UserPermissionType.ldapQuery )
                        .ldapQuery( item.getLdapQuery() )
                        .ldapBase( userIdentity.getLdapProfileID() )
                        .build();

                final boolean queryMatch = UserPermissionUtility.testUserPermission(
                        pwmRequest.getPwmRequestContext(),
                        pwmRequest.getPwmSession().getUserInfo().getUserIdentity(),
                        userPermission
                );

                if ( queryMatch )
                {
                    visibleItems.put( item.getLabel(), item );
                }
            }
        }

        return visibleItems;
    }

    private void handleUserSelection(
            final PwmRequest pwmRequest,
            final ShortcutsBean shortcutsBean
    )
            throws PwmUnrecoverableException,  IOException, ServletException
    {
        final PwmSession pwmSession = pwmRequest.getPwmSession();

        final String link = pwmRequest.readParameterAsString( "link" );
        final Map<String, ShortcutItem> visibleItems = shortcutsBean.getVisibleItems();

        if ( link != null && visibleItems.containsKey( link ) )
        {
            final ShortcutItem item = visibleItems.get( link );

            StatisticsClient.incrementStat( pwmRequest, Statistic.SHORTCUTS_SELECTED );
            LOGGER.trace( pwmRequest, () -> "shortcut link selected: " + link + ", setting link for 'forwardURL' to " + item.getShortcutURI() );
            pwmSession.getSessionStateBean().setForwardURL( item.getShortcutURI().toString() );

            pwmRequest.getPwmResponse().sendRedirectToContinue();
            return;
        }

        LOGGER.error( pwmRequest, () -> "unknown/unexpected link requested to " + link );
        pwmRequest.forwardToJsp( JspUrl.SHORTCUT );
    }
}
