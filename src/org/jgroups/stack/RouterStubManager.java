/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jgroups.stack;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.PhysicalAddress;
import org.jgroups.annotations.GuardedBy;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.util.TimeScheduler;
import org.jgroups.util.Util;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

/**
 * Manages a list of RouterStubs (e.g. health checking, reconnecting etc.
 * @author Vladimir Blagojevic
 * @author Bela Ban
 */
public class RouterStubManager implements RouterStub.ConnectionListener {

    @GuardedBy("reconnectorLock")
    private final ConcurrentMap<RouterStub,Future<?>> futures=new ConcurrentHashMap<RouterStub,Future<?>>();
    private final List<RouterStub> stubs;
    
    private final Protocol owner;
    private final TimeScheduler timer;
    private final String channelName;
    private final Address logicalAddress;
    private final long interval;

    protected final Log log;

    public RouterStubManager(Protocol owner, String channelName, Address logicalAddress, long interval) {
        this.owner = owner;
        this.stubs = new CopyOnWriteArrayList<RouterStub>();
        this.log = LogFactory.getLog(owner.getClass());     
        this.timer = owner.getTransport().getTimer();
        this.channelName = channelName;
        this.logicalAddress = logicalAddress;
        this.interval = interval;
    }
    
    private RouterStubManager(Protocol p) {
       this(p,null,null,0L);
    }
    
    public List<RouterStub> getStubs(){
        return stubs;
    }
    
    public RouterStub createAndRegisterStub(String routerHost, int routerPort, InetAddress bindAddress) {
        RouterStub s = new RouterStub(routerHost,routerPort,bindAddress,this);
        if (log.isDebugEnabled()) log.debug("NC Creating stub " + s);                        
        unregisterAndDestroyStub(s);       
        stubs.add(s);   
        return s;
    }
    
    public void registerStub(RouterStub s) {        
        if (log.isDebugEnabled()) log.debug("NC Registering stub " + s);                        
        unregisterAndDestroyStub(s);        
        stubs.add(s);           
    }
    
    public RouterStub unregisterStub(final RouterStub stub) {
        if (log.isDebugEnabled()) log.debug("NC Attempting to unregistering stub " + stub);     
        if(stub == null)
            throw new IllegalArgumentException("Cannot remove null stub");
        RouterStub found=null;
        for (RouterStub s: stubs) {
            if (s.equals(stub)) {
                found=s;
                break;
            }
        }
        if(found != null)
            stubs.remove(found);
        return found;
    }
    
    public boolean unregisterAndDestroyStub(final RouterStub stub) {
        RouterStub unregisteredStub = unregisterStub(stub);
        if(unregisteredStub != null) {
            unregisteredStub.destroy();
            return true;
        }
        return false;
    }
    
    public void disconnectStubs() {
        for (RouterStub stub : stubs) {
            try {
                if (log.isDebugEnabled()) log.debug("NC Disconnecting stub " + stub);                        
                stub.disconnect(channelName, logicalAddress);                
            } catch (Exception e) {
            }
        }       
    }
    
    public void destroyStubs() {
        for (RouterStub s : stubs) {
            stopReconnecting(s);
            s.destroy();            
        }
        stubs.clear();
    }

    public void startReconnecting(final RouterStub stub) {
        Future<?> f = futures.remove(stub);
        if (f != null)
            f.cancel(true);

        final Runnable reconnector = new Runnable() {
            public void run() {
                try {
                    if (log.isDebugEnabled()) log.debug("Reconnecting " + stub);                        
                    String logical_name = org.jgroups.util.UUID.get(logicalAddress);
                    PhysicalAddress physical_addr = (PhysicalAddress) owner.down(new Event(
                                    Event.GET_PHYSICAL_ADDRESS, logicalAddress));
                    List<PhysicalAddress> physical_addrs = Arrays.asList(physical_addr);
                    stub.connect(channelName, logicalAddress, logical_name, physical_addrs);
                    if (log.isDebugEnabled()) log.debug("Reconnected " + stub);                        
                } catch (Throwable ex) {
                    if (log.isWarnEnabled())
                        log.warn("failed reconnecting stub to GR at "+ stub.getGossipRouterAddress() + ": " + ex);
                }
            }
        };
        f = timer.scheduleWithFixedDelay(reconnector, 0, interval, TimeUnit.MILLISECONDS);
        futures.putIfAbsent(stub, f);
    }

    public void stopReconnecting(final RouterStub stub) {
        Future<?> f = futures.get(stub);
        if (f != null) {
            f.cancel(true);
            futures.remove(stub);
        }

        final Runnable pinger = new Runnable() {
            public void run() {
                try {
                    if(log.isDebugEnabled()) log.debug("Pinging " + stub);                        
                    stub.checkConnection();
                    if(log.isDebugEnabled()) log.debug("Pinged " + stub);                        
                } catch (Throwable ex) {
                    if (log.isWarnEnabled())
                        log.warn("failed pinging stub, GR at " + stub.getGossipRouterAddress()+ ": " + ex);
                }
            }
        };
        f = timer.scheduleWithFixedDelay(pinger, 1000, interval, TimeUnit.MILLISECONDS);
        futures.putIfAbsent(stub, f);
    }
   

    public void connectionStatusChange(RouterStub stub, RouterStub.ConnectionStatus newState) {
        if (newState == RouterStub.ConnectionStatus.CONNECTION_BROKEN) {
            if (log.isDebugEnabled()) log.debug("NC Connection broke with stub " + stub);                        
            stub.interrupt();
            stub.destroy();
            startReconnecting(stub);
        } else if (newState == RouterStub.ConnectionStatus.CONNECTED) {
            if (log.isDebugEnabled()) log.debug("NC Connection established with stub " + stub);                        
            stopReconnecting(stub);
        } else if (newState == RouterStub.ConnectionStatus.DISCONNECTED) {
            // wait for disconnect ack;
            try {
                stub.join(interval);
            } catch (InterruptedException e) {
            }
        }
    }
    
    public static RouterStubManager emptyGossipClientStubManager(Protocol p) {
        return new RouterStubManager(p);
    }

    public String printStubs() {
        return Util.printListWithDelimiter(stubs, ", ");
    }
}
