package com.conveyal.datatools.editor.controllers.api;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.conveyal.datatools.editor.controllers.Application;
import com.conveyal.datatools.editor.controllers.Base;
import com.conveyal.datatools.editor.controllers.Secure;
import com.conveyal.datatools.editor.controllers.Security;
import com.conveyal.datatools.editor.datastore.AgencyTx;
import com.conveyal.datatools.editor.datastore.GlobalTx;
import com.conveyal.datatools.editor.datastore.VersionedDataStore;
import com.conveyal.datatools.editor.models.transit.Route;
import com.conveyal.datatools.editor.models.transit.Trip;
import com.conveyal.datatools.editor.models.transit.TripPattern;
import org.mapdb.Fun;
import org.mapdb.Fun.Tuple2;

import java.util.Collection;
import java.util.Set;
import spark.Request;
import spark.Response;

import static spark.Spark.*;


public class RouteController {



    public static void getRoute(String id, String agencyId) {
        if (agencyId == null)
            agencyId = session.get("agencyId");

        if (agencyId == null) {
            halt(400);
            return;
        }

        final AgencyTx tx = VersionedDataStore.getAgencyTx(agencyId);

        try {
            if (id != null) {
                if (!tx.routes.containsKey(id)) {
                    tx.rollback();
                    halt(400);
                    return;
                }

                Route route = tx.routes.get(id);

                route.addDerivedInfo(tx);

                renderJSON(Base.toJson(route, false));
            }
            else {
                Route[] ret = tx.routes.values().toArray(new Route[tx.routes.size()]);

                for (Route r : ret) {
                    r.addDerivedInfo(tx);
                }

                String json = Base.toJson(ret, false);
                tx.rollback();
                renderJSON(json);
            }
        } catch (Exception e) {
            tx.rollbackIfOpen();
            e.printStackTrace();
            halt(400);
        }
    }

    public static void createRoute() {
        Route route;

        try {
            route = Base.mapper.readValue(params.get("body"), Route.class);
            
            GlobalTx gtx = VersionedDataStore.getGlobalTx();
            if (!gtx.agencies.containsKey(route.agencyId)) {
                gtx.rollback();
                halt(400);
                return;
            }
            
            if (session.contains("agencyId") && !session.get("agencyId").equals(route.agencyId))
                halt(400);
            
            gtx.rollback();
   
            AgencyTx tx = VersionedDataStore.getAgencyTx(route.agencyId);
            
            if (tx.routes.containsKey(route.id)) {
                tx.rollback();
                halt(400);
                return;
            }

            // check if gtfsRouteId is specified, if not create from DB id
            if(route.gtfsRouteId == null) {
                route.gtfsRouteId = "ROUTE_" + route.id;
            }
            
            tx.routes.put(route.id, route);
            tx.commit();

            renderJSON(Base.toJson(route, false));
        } catch (Exception e) {
            e.printStackTrace();
            halt(400);
        }
    }


    public static void updateRoute() {
        Route route;

        try {
            route = Base.mapper.readValue(params.get("body"), Route.class);
   
            AgencyTx tx = VersionedDataStore.getAgencyTx(route.agencyId);
            
            if (!tx.routes.containsKey(route.id)) {
                tx.rollback();
                halt(404);
                return;
            }
            
            if (session.contains("agencyId") && !session.get("agencyId").equals(route.agencyId))
                halt(400);

            // check if gtfsRouteId is specified, if not create from DB id
            if(route.gtfsRouteId == null) {
                route.gtfsRouteId = "ROUTE_" + route.id;
            }
            
            tx.routes.put(route.id, route);
            tx.commit();

            renderJSON(Base.toJson(route, false));
        } catch (Exception e) {
            e.printStackTrace();
            halt(400);
        }
    }

    public static void deleteRoute(String id, String agencyId) {
        if (agencyId == null)
            agencyId = session.get("agencyId");

        if(id == null || agencyId == null)
            halt(400);

        AgencyTx tx = VersionedDataStore.getAgencyTx(agencyId);
        

        
        try {
            if (!tx.routes.containsKey(id)) {
                tx.rollback();
                halt(404);
                return;
            }
            
            Route r = tx.routes.get(id);

            // delete affected trips
            Set<Tuple2<String, String>> affectedTrips = tx.tripsByRoute.subSet(new Tuple2(r.id, null), new Tuple2(r.id, Fun.HI));
            for (Tuple2<String, String> trip : affectedTrips) {
                tx.trips.remove(trip.b);
            }
            
            // delete affected patterns
            // note that all the trips on the patterns will have already been deleted above
            Set<Tuple2<String, String>> affectedPatts = tx.tripPatternsByRoute.subSet(new Tuple2(r.id, null), new Tuple2(r.id, Fun.HI));
            for (Tuple2<String, String> tp : affectedPatts) {
                tx.tripPatterns.remove(tp.b);
            }
            
            tx.routes.remove(id);
            tx.commit();
            return true; // ok();
        } catch (Exception e) {
            tx.rollback();
            e.printStackTrace();
            error(e);
        }
    }
    
    /** merge route from into route into, for the given agency ID */
    public static void mergeRoutes (String from, String into, String agencyId) {
        if (agencyId == null)
            agencyId = session.get("agencyId");

        if (agencyId == null || from == null || into == null)
            halt(400);

        final AgencyTx tx = VersionedDataStore.getAgencyTx(agencyId);

        try {
            // ensure the routes exist
            if (!tx.routes.containsKey(from) || !tx.routes.containsKey(into)) {
                tx.rollback();
                halt(400);
            }

            // get all the trip patterns for route from
            // note that we clone them here so we can later modify them
            Collection<TripPattern> tps = Collections2.transform(
                    tx.tripPatternsByRoute.subSet(new Tuple2(from, null), new Tuple2(from, Fun.HI)),
                    new Function<Tuple2<String, String>, TripPattern>() {
                        @Override
                        public TripPattern apply(Tuple2<String, String> input) {
                            try {
                                return tx.tripPatterns.get(input.b).clone();
                            } catch (CloneNotSupportedException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }
                        }
                    });

             for (TripPattern tp : tps) {
                 tp.routeId = into;
                 tx.tripPatterns.put(tp.id, tp);
             }

             // now move all the trips
             Collection<Trip> ts = Collections2.transform(
                     tx.tripsByRoute.subSet(new Tuple2(from, null), new Tuple2(from, Fun.HI)),
                     new Function<Tuple2<String, String>, Trip>() {
                         @Override
                         public Trip apply(Tuple2<String, String> input) {
                             try {
                                return tx.trips.get(input.b).clone();
                            } catch (CloneNotSupportedException e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }
                         }
                     });

             for (Trip t : ts) {
                 t.routeId = into;
                 tx.trips.put(t.id, t);
             }

             tx.routes.remove(from);

             tx.commit();
             return true; // ok();
        }
        catch (Exception e) {
            e.printStackTrace();
            tx.rollback();
            throw e;
        }
    }
}
