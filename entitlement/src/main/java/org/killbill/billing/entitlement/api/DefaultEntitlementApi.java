/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.entitlement.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.AccountInternalApi;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.PlanPhasePriceOverride;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.AccountEventsStreams;
import org.killbill.billing.entitlement.EntitlementService;
import org.killbill.billing.entitlement.EventsStream;
import org.killbill.billing.entitlement.api.EntitlementPluginExecution.WithEntitlementPlugin;
import org.killbill.billing.entitlement.api.svcs.DefaultEntitlementApiBase;
import org.killbill.billing.entitlement.block.BlockingChecker;
import org.killbill.billing.entitlement.dao.BlockingStateDao;
import org.killbill.billing.entitlement.engine.core.EntitlementUtils;
import org.killbill.billing.entitlement.engine.core.EventsStreamBuilder;
import org.killbill.billing.entitlement.plugin.api.EntitlementContext;
import org.killbill.billing.entitlement.plugin.api.OperationType;
import org.killbill.billing.junction.DefaultBlockingState;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.security.api.SecurityApi;
import org.killbill.billing.subscription.api.SubscriptionBase;
import org.killbill.billing.subscription.api.SubscriptionBaseInternalApi;
import org.killbill.billing.subscription.api.transfer.SubscriptionBaseTransferApi;
import org.killbill.billing.subscription.api.transfer.SubscriptionBaseTransferApiException;
import org.killbill.billing.subscription.api.user.SubscriptionBaseApiException;
import org.killbill.billing.subscription.api.user.SubscriptionBaseBundle;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.bus.api.PersistentBus;
import org.killbill.clock.Clock;
import org.killbill.notificationq.api.NotificationQueueService;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class DefaultEntitlementApi extends DefaultEntitlementApiBase implements EntitlementApi {

    public static final String ENT_STATE_START = "ENT_STARTED";
    public static final String ENT_STATE_BLOCKED = "ENT_BLOCKED";
    public static final String ENT_STATE_CLEAR = "ENT_CLEAR";
    public static final String ENT_STATE_CANCELLED = "ENT_CANCELLED";

    private final SubscriptionBaseInternalApi subscriptionBaseInternalApi;
    private final SubscriptionBaseTransferApi subscriptionBaseTransferApi;
    private final Clock clock;
    private final InternalCallContextFactory internalCallContextFactory;
    private final BlockingChecker checker;
    private final BlockingStateDao blockingStateDao;
    private final EntitlementDateHelper dateHelper;
    private final PersistentBus eventBus;
    private final EventsStreamBuilder eventsStreamBuilder;
    private final EntitlementUtils entitlementUtils;
    private final NotificationQueueService notificationQueueService;
    private final EntitlementPluginExecution pluginExecution;
    private final SecurityApi securityApi;

    @Inject
    public DefaultEntitlementApi(final PersistentBus eventBus, final InternalCallContextFactory internalCallContextFactory,
                                 final SubscriptionBaseTransferApi subscriptionTransferApi, final SubscriptionBaseInternalApi subscriptionInternalApi,
                                 final AccountInternalApi accountApi, final BlockingStateDao blockingStateDao, final Clock clock,
                                 final BlockingChecker checker, final NotificationQueueService notificationQueueService,
                                 final EventsStreamBuilder eventsStreamBuilder, final EntitlementUtils entitlementUtils,
                                 final EntitlementPluginExecution pluginExecution,
                                 final SecurityApi securityApi) {
        super(eventBus, null, pluginExecution, internalCallContextFactory, subscriptionInternalApi, accountApi, blockingStateDao, clock, checker, notificationQueueService, eventsStreamBuilder, entitlementUtils, securityApi);
        this.eventBus = eventBus;
        this.internalCallContextFactory = internalCallContextFactory;
        this.subscriptionBaseInternalApi = subscriptionInternalApi;
        this.subscriptionBaseTransferApi = subscriptionTransferApi;
        this.clock = clock;
        this.checker = checker;
        this.blockingStateDao = blockingStateDao;
        this.notificationQueueService = notificationQueueService;
        this.eventsStreamBuilder = eventsStreamBuilder;
        this.entitlementUtils = entitlementUtils;
        this.pluginExecution = pluginExecution;
        this.securityApi = securityApi;
        this.dateHelper = new EntitlementDateHelper(clock);
    }

    @Override
    public Entitlement createBaseEntitlement(final UUID accountId, final PlanPhaseSpecifier planPhaseSpecifier, final String externalKey, final List<PlanPhasePriceOverride> overrides,
                                             @Nullable final LocalDate entitlementEffectiveDate, @Nullable  LocalDate billingEffectiveDate, final boolean isMigrated,
                                             final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        final EntitlementSpecifier entitlementSpecifier = new DefaultEntitlementSpecifier(planPhaseSpecifier, overrides);
        final List<EntitlementSpecifier> entitlementSpecifierList = new ArrayList<EntitlementSpecifier>();
        entitlementSpecifierList.add(entitlementSpecifier);
        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CREATE_SUBSCRIPTION,
                                                                               accountId,
                                                                               null,
                                                                               null,
                                                                               externalKey,
                                                                               entitlementSpecifierList,
                                                                               entitlementEffectiveDate,
                                                                               billingEffectiveDate,
                                                                               null,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> createBaseEntitlementWithPlugin = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(accountId, callContext);
                try {
                    if (entitlementUtils.getFirstActiveSubscriptionIdForKeyOrNull(externalKey, contextWithValidAccountRecordId) != null) {
                        throw new EntitlementApiException(new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_ACTIVE_BUNDLE_KEY_EXISTS, externalKey));
                    }

                    final SubscriptionBaseBundle bundle = subscriptionBaseInternalApi.createBundleForAccount(accountId, externalKey, contextWithValidAccountRecordId);

                    final DateTime billingRequestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getBillingEffectiveDate(), contextWithValidAccountRecordId);
                    final EntitlementSpecifier specifier = getFirstEntitlementSpecifier(updatedPluginContext.getEntitlementSpecifiers());
                    final SubscriptionBase subscription = subscriptionBaseInternalApi.createSubscription(bundle.getId(), specifier.getPlanPhaseSpecifier(), specifier.getOverrides(), billingRequestedDate, isMigrated, contextWithValidAccountRecordId);

                    final DateTime entitlementRequestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEntitlementEffectiveDate(), contextWithValidAccountRecordId);
                    final BlockingState newBlockingState = new DefaultBlockingState(subscription.getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_START, EntitlementService.ENTITLEMENT_SERVICE_NAME, false, false, false, entitlementRequestedDate);
                    entitlementUtils.setBlockingStatesAndPostBlockingTransitionEvent(ImmutableList.<BlockingState>of(newBlockingState), subscription.getBundleId(), contextWithValidAccountRecordId);

                    return new DefaultEntitlement(accountId, subscription.getId(), eventsStreamBuilder, entitlementApi, pluginExecution,
                                                  blockingStateDao, subscriptionBaseInternalApi, checker, notificationQueueService,
                                                  entitlementUtils, dateHelper, clock, securityApi, internalCallContextFactory, callContext);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }
            }
        };
        return pluginExecution.executeWithPlugin(createBaseEntitlementWithPlugin, pluginContext);
    }

    private EntitlementSpecifier getFirstEntitlementSpecifier(final List<EntitlementSpecifier> entitlementSpecifiers) throws SubscriptionBaseApiException {
        if ((entitlementSpecifiers == null) || entitlementSpecifiers.isEmpty()) {
            throw new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_INVALID_ENTITLEMENT_SPECIFIER);
        }
        return entitlementSpecifiers.get(0);
    }

    @Override
    public Entitlement createBaseEntitlementWithAddOns(final UUID accountId, final String externalKey, final Iterable<EntitlementSpecifier> entitlementSpecifiers,
                                                       @Nullable LocalDate entitlementEffectiveDate,  @Nullable LocalDate billingEffectiveDate, final boolean isMigrated,
                                                       final Iterable<PluginProperty> properties, final CallContext callContext)
            throws EntitlementApiException {

        final EntitlementSpecifier baseSpecifier = Iterables.tryFind(entitlementSpecifiers, new Predicate<EntitlementSpecifier>() {
            @Override
            public boolean apply(final EntitlementSpecifier specifier) {
                return specifier.getPlanPhaseSpecifier() != null && ProductCategory.BASE.equals(specifier.getPlanPhaseSpecifier().getProductCategory());
            }
        }).orNull();

        if (baseSpecifier == null) {
            throw new EntitlementApiException(new IllegalArgumentException(), ErrorCode.SUB_CREATE_NO_BP.getCode(), "Missing Base Subscription.");
        }

        final List<EntitlementSpecifier> entitlementSpecifierList = new ArrayList<EntitlementSpecifier>();
        Iterables.addAll(entitlementSpecifierList, entitlementSpecifiers);

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CREATE_SUBSCRIPTIONS_WITH_AO,
                                                                               accountId,
                                                                               null,
                                                                               null,
                                                                               externalKey,
                                                                               entitlementSpecifierList,
                                                                               entitlementEffectiveDate,
                                                                               billingEffectiveDate,
                                                                               null,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> createBaseEntitlementWithAddOn = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(accountId, callContext);

                try {
                    if (entitlementUtils.getFirstActiveSubscriptionIdForKeyOrNull(externalKey, contextWithValidAccountRecordId) != null) {
                        throw new EntitlementApiException(new SubscriptionBaseApiException(ErrorCode.SUB_CREATE_ACTIVE_BUNDLE_KEY_EXISTS, externalKey));
                    }

                    final SubscriptionBaseBundle bundle = subscriptionBaseInternalApi.createBundleForAccount(accountId, externalKey, contextWithValidAccountRecordId);

                    final DateTime billingRequestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getBillingEffectiveDate(), contextWithValidAccountRecordId);
                    final List<SubscriptionBase> subscriptionBases = subscriptionBaseInternalApi.createBaseSubscriptionWithAddOns(bundle.getId(), entitlementSpecifiers, billingRequestedDate, isMigrated, contextWithValidAccountRecordId);

                    final DateTime entitlementRequestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEntitlementEffectiveDate(), contextWithValidAccountRecordId);
                    final List<BlockingState> blockingStates = new ArrayList<BlockingState>();
                    for (final SubscriptionBase cur : subscriptionBases) {
                        final BlockingState blockingState = new DefaultBlockingState(cur.getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_START, EntitlementService.ENTITLEMENT_SERVICE_NAME, false, false, false, entitlementRequestedDate);
                        blockingStates.add(blockingState);
                    }
                    final SubscriptionBase subscriptionBaseBP = subscriptionBases.get(0);
                    entitlementUtils.setBlockingStatesAndPostBlockingTransitionEvent(blockingStates, subscriptionBaseBP.getBundleId(), contextWithValidAccountRecordId);


                    return new DefaultEntitlement(accountId, subscriptionBaseBP.getId(), eventsStreamBuilder, entitlementApi, pluginExecution,
                                                  blockingStateDao, subscriptionBaseInternalApi, checker, notificationQueueService,
                                                  entitlementUtils, dateHelper, clock, securityApi, internalCallContextFactory, callContext);

                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }

            }
        };
        return pluginExecution.executeWithPlugin(createBaseEntitlementWithAddOn, pluginContext);
    }

    @Override
    public Entitlement addEntitlement(final UUID bundleId, final PlanPhaseSpecifier planPhaseSpecifier, final List<PlanPhasePriceOverride> overrides, @Nullable final LocalDate entitlementEffectiveDate, @Nullable final LocalDate billingEffectiveDate,
            final boolean isMigrated, final Iterable<PluginProperty> properties, final CallContext callContext) throws EntitlementApiException {

        final EntitlementSpecifier entitlementSpecifier = new DefaultEntitlementSpecifier(planPhaseSpecifier, overrides);
        final List<EntitlementSpecifier> entitlementSpecifierList = new ArrayList<EntitlementSpecifier>();
        entitlementSpecifierList.add(entitlementSpecifier);
        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.CREATE_SUBSCRIPTION,
                                                                               null,
                                                                               null,
                                                                               bundleId,
                                                                               null,
                                                                               entitlementSpecifierList,
                                                                               entitlementEffectiveDate,
                                                                               billingEffectiveDate,
                                                                               null,
                                                                               properties,
                                                                               callContext);

        final WithEntitlementPlugin<Entitlement> addEntitlementWithPlugin = new WithEntitlementPlugin<Entitlement>() {
            @Override
            public Entitlement doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                final EventsStream eventsStreamForBaseSubscription = eventsStreamBuilder.buildForBaseSubscription(bundleId, callContext);

                // Check the base entitlement state is active
                if (!eventsStreamForBaseSubscription.isEntitlementActive()) {
                    throw new EntitlementApiException(ErrorCode.SUB_GET_NO_SUCH_BASE_SUBSCRIPTION, bundleId);
                }

                // Check the base entitlement state is not blocked
                if (eventsStreamForBaseSubscription.isBlockChange()) {
                    throw new EntitlementApiException(new BlockingApiException(ErrorCode.BLOCK_BLOCKED_ACTION, BlockingChecker.ACTION_CHANGE, BlockingChecker.TYPE_SUBSCRIPTION, eventsStreamForBaseSubscription.getEntitlementId().toString()));
                }

                final DateTime billingRequestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getBillingEffectiveDate(), eventsStreamForBaseSubscription.getInternalTenantContext());
                try {
                    final InternalCallContext context = internalCallContextFactory.createInternalCallContext(eventsStreamForBaseSubscription.getAccountId(), callContext);
                    final EntitlementSpecifier specifier = getFirstEntitlementSpecifier(updatedPluginContext.getEntitlementSpecifiers());
                    final SubscriptionBase subscription = subscriptionBaseInternalApi.createSubscription(bundleId, specifier.getPlanPhaseSpecifier(), specifier.getOverrides(), billingRequestedDate, isMigrated, context);

                    final DateTime entitlementRequestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEntitlementEffectiveDate(), eventsStreamForBaseSubscription.getInternalTenantContext());
                    final BlockingState newBlockingState = new DefaultBlockingState(subscription.getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_START, EntitlementService.ENTITLEMENT_SERVICE_NAME, false, false, false, entitlementRequestedDate);
                    entitlementUtils.setBlockingStatesAndPostBlockingTransitionEvent(ImmutableList.<BlockingState>of(newBlockingState), subscription.getBundleId(), context);


                    return new DefaultEntitlement(eventsStreamForBaseSubscription.getAccountId(), subscription.getId(), eventsStreamBuilder, entitlementApi, pluginExecution,
                                                  blockingStateDao, subscriptionBaseInternalApi, checker, notificationQueueService,
                                                  entitlementUtils, dateHelper, clock, securityApi, internalCallContextFactory, callContext);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }
            }
        };
        return pluginExecution.executeWithPlugin(addEntitlementWithPlugin, pluginContext);
    }

    @Override
    public List<EntitlementAOStatusDryRun> getDryRunStatusForChange(final UUID bundleId, final String targetProductName, @Nullable final LocalDate effectiveDate, final TenantContext context) throws EntitlementApiException {
        final InternalTenantContext internalContext = internalCallContextFactory.createInternalTenantContext(bundleId, ObjectType.BUNDLE, context);
        try {
            final SubscriptionBaseBundle bundle = subscriptionBaseInternalApi.getBundleFromId(bundleId, internalContext);
            final SubscriptionBase baseSubscription = subscriptionBaseInternalApi.getBaseSubscription(bundleId, internalContext);

            final InternalTenantContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalTenantContext(bundle.getAccountId(), context);
            final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(effectiveDate, contextWithValidAccountRecordId);
            return subscriptionBaseInternalApi.getDryRunChangePlanStatus(baseSubscription.getId(), targetProductName, requestedDate, contextWithValidAccountRecordId);
        } catch (final SubscriptionBaseApiException e) {
            throw new EntitlementApiException(e);
        }
    }

    @Override
    public Entitlement getEntitlementForId(final UUID entitlementId, final TenantContext tenantContext) throws EntitlementApiException {
        final InternalTenantContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalTenantContext(entitlementId, ObjectType.SUBSCRIPTION, tenantContext);
        return super.getEntitlementForId(entitlementId, contextWithValidAccountRecordId);
    }

    @Override
    public List<Entitlement> getAllEntitlementsForBundle(final UUID bundleId, final TenantContext tenantContext) throws EntitlementApiException {
        final InternalTenantContext internalContext = internalCallContextFactory.createInternalTenantContext(bundleId, ObjectType.BUNDLE, tenantContext);
        final UUID accountId;
        try {
            accountId = subscriptionBaseInternalApi.getBundleFromId(bundleId, internalContext).getAccountId();
        } catch (final SubscriptionBaseApiException e) {
            throw new EntitlementApiException(e);
        }

        return ImmutableList.<Entitlement>copyOf(Iterables.<Entitlement>filter(getAllEntitlementsForAccountId(accountId, tenantContext),
                                                                               new Predicate<Entitlement>() {
                                                                                   @Override
                                                                                   public boolean apply(final Entitlement input) {
                                                                                       return bundleId.equals(input.getBundleId());
                                                                                   }
                                                                               }));
    }

    @Override
    public List<Entitlement> getAllEntitlementsForAccountIdAndExternalKey(final UUID accountId, final String externalKey, final TenantContext tenantContext) throws EntitlementApiException {
        // getAllEntitlementsForAccount should be fast (uses account_record_id)
        return ImmutableList.<Entitlement>copyOf(Iterables.<Entitlement>filter(getAllEntitlementsForAccountId(accountId, tenantContext),
                                                                               new Predicate<Entitlement>() {
                                                                                   @Override
                                                                                   public boolean apply(final Entitlement input) {
                                                                                       return externalKey.equals(input.getExternalKey());
                                                                                   }
                                                                               }));
    }

    @Override
    public List<Entitlement> getAllEntitlementsForAccountId(final UUID accountId, final TenantContext tenantContext) throws EntitlementApiException {
        final EntitlementApi entitlementApi = this;
        final InternalTenantContext context = internalCallContextFactory.createInternalTenantContext(accountId, tenantContext);

        final AccountEventsStreams accountEventsStreams = eventsStreamBuilder.buildForAccount(context);
        final List<EventsStream> eventsStreams = ImmutableList.<EventsStream>copyOf(Iterables.<EventsStream>concat(accountEventsStreams.getEventsStreams().values()));
        return Lists.<EventsStream, Entitlement>transform(eventsStreams,
                                                          new Function<EventsStream, Entitlement>() {
                                                              @Override
                                                              public Entitlement apply(final EventsStream eventsStream) {
                                                                  return new DefaultEntitlement(eventsStream, eventsStreamBuilder, entitlementApi, pluginExecution,
                                                                                                blockingStateDao, subscriptionBaseInternalApi, checker, notificationQueueService,
                                                                                                entitlementUtils, dateHelper, clock, securityApi, context, internalCallContextFactory);
                                                              }
                                                          });
    }

    @Override
    public void pause(final UUID bundleId, final LocalDate localEffectiveDate, final Iterable<PluginProperty> properties, final CallContext context) throws EntitlementApiException {

        final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(bundleId, ObjectType.BUNDLE, context);
        super.pause(bundleId, localEffectiveDate, properties, contextWithValidAccountRecordId);
    }

    @Override
    public void resume(final UUID bundleId, final LocalDate localEffectiveDate, final Iterable<PluginProperty> properties, final CallContext context) throws EntitlementApiException {

        final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(bundleId, ObjectType.BUNDLE, context);
        super.resume(bundleId, localEffectiveDate, properties, contextWithValidAccountRecordId);

    }

    @Override
    public UUID transferEntitlements(final UUID sourceAccountId, final UUID destAccountId, final String externalKey, final LocalDate effectiveDate, final Iterable<PluginProperty> properties, final CallContext context) throws EntitlementApiException {
        return transferEntitlementsOverrideBillingPolicy(sourceAccountId, destAccountId, externalKey, effectiveDate, BillingActionPolicy.IMMEDIATE, properties, context);
    }

    @Override
    public UUID transferEntitlementsOverrideBillingPolicy(final UUID sourceAccountId, final UUID destAccountId, final String externalKey, @Nullable final LocalDate effectiveDate, final BillingActionPolicy billingPolicy, final Iterable<PluginProperty> properties, final CallContext context) throws EntitlementApiException {

        final EntitlementContext pluginContext = new DefaultEntitlementContext(OperationType.TRANSFER_BUNDLE,
                                                                               sourceAccountId,
                                                                               destAccountId,
                                                                               null,
                                                                               externalKey,
                                                                               new ArrayList<EntitlementSpecifier>(),
                                                                               effectiveDate,
                                                                               effectiveDate,
                                                                               null,
                                                                               properties,
                                                                               context);

        final WithEntitlementPlugin<UUID> transferWithPlugin = new WithEntitlementPlugin<UUID>() {
            @Override
            public UUID doCall(final EntitlementApi entitlementApi, final EntitlementContext updatedPluginContext) throws EntitlementApiException {
                final boolean cancelImm;
                switch (billingPolicy) {
                    case IMMEDIATE:
                        cancelImm = true;
                        break;
                    case END_OF_TERM:
                        cancelImm = false;
                        break;
                    default:
                        throw new RuntimeException("Unexpected billing policy " + billingPolicy);
                }

                final InternalCallContext contextWithSourceAccountRecordId = internalCallContextFactory.createInternalCallContext(sourceAccountId, context);
                try {

                    final UUID activeSubscriptionIdForKey = entitlementUtils.getFirstActiveSubscriptionIdForKeyOrNull(externalKey, contextWithSourceAccountRecordId);
                    final SubscriptionBase baseSubscription = activeSubscriptionIdForKey != null ?
                                                              subscriptionBaseInternalApi.getSubscriptionFromId(activeSubscriptionIdForKey, contextWithSourceAccountRecordId) : null;
                    final SubscriptionBaseBundle baseBundle = baseSubscription != null ?
                                                              subscriptionBaseInternalApi.getBundleFromId(baseSubscription.getBundleId(), contextWithSourceAccountRecordId) : null;

                    if (baseBundle == null || !baseBundle.getAccountId().equals(sourceAccountId)) {
                        throw new EntitlementApiException(new SubscriptionBaseApiException(ErrorCode.SUB_GET_INVALID_BUNDLE_KEY, externalKey));
                    }

                    final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getBillingEffectiveDate(), contextWithSourceAccountRecordId);
                    final SubscriptionBaseBundle newBundle = subscriptionBaseTransferApi.transferBundle(sourceAccountId, destAccountId, externalKey, requestedDate, true, cancelImm, context);


                    final Map<BlockingState, UUID> blockingStates = new HashMap<BlockingState, UUID>();

                    // Block all associated subscriptions - TODO Do we want to block the bundle as well (this will add an extra STOP_ENTITLEMENT event in the bundle timeline stream)?
                    // Note that there is no un-transfer at the moment, so we effectively add a blocking state on disk for all subscriptions
                    for (final SubscriptionBase subscriptionBase : subscriptionBaseInternalApi.getSubscriptionsForBundle(baseBundle.getId(), null, contextWithSourceAccountRecordId)) {
                        final BlockingState blockingState = new DefaultBlockingState(subscriptionBase.getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_CANCELLED, EntitlementService.ENTITLEMENT_SERVICE_NAME, true, true, false, requestedDate);
                        blockingStates.put(blockingState, subscriptionBase.getBundleId());
                    }
                    entitlementUtils.setBlockingStateAndPostBlockingTransitionEvent(blockingStates, contextWithSourceAccountRecordId);

                    // Add blocking events for transferred subscriptions..
                    final InternalCallContext contextWithDestAccountRecordId = internalCallContextFactory.createInternalCallContext(destAccountId, context);

                    blockingStates.clear();
                    final DateTime entitlementRequestedDate = dateHelper.fromLocalDateAndReferenceTime(updatedPluginContext.getEntitlementEffectiveDate(), contextWithDestAccountRecordId);
                    for (final SubscriptionBase subscriptionBase : subscriptionBaseInternalApi.getSubscriptionsForBundle(newBundle.getId(), null, contextWithDestAccountRecordId)) {
                        final BlockingState newBlockingState = new DefaultBlockingState(subscriptionBase.getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_START, EntitlementService.ENTITLEMENT_SERVICE_NAME, false, false, false, entitlementRequestedDate);
                        blockingStates.put(newBlockingState, subscriptionBase.getBundleId());
                    }
                    entitlementUtils.setBlockingStateAndPostBlockingTransitionEvent(blockingStates, contextWithDestAccountRecordId);


                    return newBundle.getId();
                } catch (final SubscriptionBaseTransferApiException e) {
                    throw new EntitlementApiException(e);
                } catch (final SubscriptionBaseApiException e) {
                    throw new EntitlementApiException(e);
                }
            }
        };
        return pluginExecution.executeWithPlugin(transferWithPlugin, pluginContext);
    }
}
