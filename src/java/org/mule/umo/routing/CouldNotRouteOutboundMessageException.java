/*
 * $Header$
 * $Revision$
 * $Date$
 * ------------------------------------------------------------------------------------------------------
 *
 * Copyright (c) Cubis Limited. All rights reserved.
 * http://www.cubis.co.uk
 *
 * The software in this package is published under the terms of the BSD
 * style license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */
package org.mule.umo.routing;

import org.mule.umo.UMOMessage;
import org.mule.umo.endpoint.UMOEndpoint;
import org.mule.umo.provider.DispatchException;
import org.mule.config.i18n.Message;

/**
 * <code>CouldNotRouteOutboundMessageException</code> thrown if Mule fails to
 * route the current outbound event
 *
 * @author <a href="mailto:ross.mason@cubis.co.uk">Ross Mason</a>
 * @version $Revision$
 */

public class CouldNotRouteOutboundMessageException extends RoutingException
{
    public CouldNotRouteOutboundMessageException(UMOMessage message, UMOEndpoint endpoint)
    {
        super(message, endpoint);
    }

    public CouldNotRouteOutboundMessageException(UMOMessage umoMessage, UMOEndpoint endpoint, Throwable cause)
    {
        super(umoMessage, endpoint, cause);
    }

    public CouldNotRouteOutboundMessageException(Message message, UMOMessage umoMessage, UMOEndpoint endpoint)
    {
        super(message, umoMessage, endpoint);
    }

    public CouldNotRouteOutboundMessageException(Message message, UMOMessage umoMessage, UMOEndpoint endpoint, Throwable cause)
    {
        super(message, umoMessage, endpoint, cause);
    }
}
