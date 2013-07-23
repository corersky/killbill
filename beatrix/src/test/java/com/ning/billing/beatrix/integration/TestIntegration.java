/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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

package com.ning.billing.beatrix.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.LocalDate;
import org.testng.annotations.Test;

import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountData;
import com.ning.billing.api.TestApiListener.NextEvent;
import com.ning.billing.beatrix.util.InvoiceChecker.ExpectedInvoiceItemCheck;
import com.ning.billing.beatrix.util.PaymentChecker.ExpectedPaymentCheck;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.catalog.api.PlanPhaseSpecifier;
import com.ning.billing.catalog.api.PriceListSet;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.entitlement.api.DefaultEntitlement;
import com.ning.billing.subscription.api.user.SubscriptionData;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceItemType;
import com.ning.billing.payment.api.PaymentStatus;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestIntegration extends TestIntegrationBase {

    @Test(groups = "slow")
    public void testCancelBPWithAOTheSameDay() throws Exception {

        final AccountData accountData = getAccountData(1);
        final Account account = createAccountWithNonOsgiPaymentMethod(accountData);
        accountChecker.checkAccount(account.getId(), accountData, callContext);

        // We take april as it has 30 days (easier to play with BCD)
        // Set clock to the initial start date - we implicitly assume here that the account timezone is UTC
        clock.setDay(new LocalDate(2012, 4, 1));

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        final DefaultEntitlement bpSubscription = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.INVOICE);
        // Check bundle after BP got created otherwise we get an error from auditApi.
        subscriptionChecker.checkSubscriptionCreated(bpSubscription.getId(), callContext);
        invoiceChecker.checkInvoice(account.getId(), 1, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        //
        // ADD ADD_ON ON THE SAME DAY
        //
        addAOEntitlementAndCheckForCompletion(account.getId(), "Telescopic-Scope", ProductCategory.ADD_ON, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.INVOICE, NextEvent.PAYMENT);
        Invoice invoice = invoiceChecker.checkInvoice(account.getId(), 2, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("399.95")));
        paymentChecker.checkPayment(account.getId(), 1, callContext, new ExpectedPaymentCheck(new LocalDate(2012, 4, 1), new BigDecimal("399.95"), PaymentStatus.SUCCESS, invoice.getId(), Currency.USD));

        //
        // CANCEL BP ON THE SAME DAY (we should have two cancellations, BP and AO)
        // There is no invoice created as we only adjust the previous invoice.
        //
        cancelEntitlementAndCheckForCompletion(bpSubscription, clock.getUTCNow(), NextEvent.CANCEL, NextEvent.CANCEL, NextEvent.INVOICE_ADJUSTMENT);
        invoiceChecker.checkInvoice(account.getId(), 2,
                                    callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 5, 1), InvoiceItemType.RECURRING, new BigDecimal("399.95")),
                                    // The second invoice should be adjusted for the AO (we paid for the full period) and since we paid we should also see a CBA
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 5, 1), InvoiceItemType.REPAIR_ADJ, new BigDecimal("-399.95")),
                                    new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 1), new LocalDate(2012, 4, 1), InvoiceItemType.CBA_ADJ, new BigDecimal("399.95")));

    }

    @Test(groups = "slow")
    public void testBasePlanCompleteWithBillingDayInPast() throws Exception {

        final int billingDay = 31;
        final DateTime initialCreationDate = new DateTime(2012, 2, 1, 0, 3, 42, 0, testTimeZone);

        log.info("Beginning test with BCD of " + billingDay);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));

        // set clock to the initial start date
        clock.setTime(initialCreationDate);
        int invoiceItemCount = 1;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        final DefaultEntitlement baseEntitlementAndCheckForCompletion = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.INVOICE);
        SubscriptionData subscription = subscriptionDataFromSubscription(baseEntitlementAndCheckForCompletion.getSubscription());
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(initialCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        // No end date for the trial item (fixed price of zero), and CTD should be today (i.e. when the trial started)
        invoiceChecker.checkChargedThroughDate(subscription.getId(), clock.getUTCToday(), callContext);

        //
        // CHANGE PLAN IMMEDIATELY AND EXPECT BOTH EVENTS: NextEvent.CHANGE NextEvent.INVOICE
        //
        subscription = subscriptionDataFromSubscription(changeEntitlementAndCheckForCompletion(subscription, "Assault-Rifle", BillingPeriod.MONTHLY, NextEvent.CHANGE, NextEvent.INVOICE));
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(initialCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), clock.getUTCToday(), callContext);

        //
        // MOVE 4 * TIME THE CLOCK
        //
        setDateAndCheckForCompletion(new DateTime(2012, 2, 28, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 2, 29, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 3, 1, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 3, 2, 23, 59, 59, 0, testTimeZone), NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 3, 2),
                                                                                                                   new LocalDate(2012, 3, 31), InvoiceItemType.RECURRING, new BigDecimal("561.25")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 3, 31), callContext);

        //
        // CHANGE PLAN EOT AND EXPECT NOTHING
        //
        subscription = subscriptionDataFromSubscription(changeEntitlementAndCheckForCompletion(subscription, "Pistol", BillingPeriod.MONTHLY));

        //
        // MOVE TIME AFTER CTD AND EXPECT BOTH EVENTS : NextEvent.CHANGE NextEvent.INVOICE
        //
        final LocalDate firstRecurringPistolDate = subscription.getChargedThroughDate().toLocalDate();
        final LocalDate secondRecurringPistolDate = firstRecurringPistolDate.plusMonths(1);
        addDaysAndCheckForCompletion(31, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 3, 31), new LocalDate(2012, 4, 30), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), secondRecurringPistolDate, callContext);

        //
        // MOVE 3 * TIME AFTER NEXT BILL CYCLE DAY AND EXPECT EVENT : NextEvent.INVOICE, NextEvent.PAYMENT
        //
        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 30), new LocalDate(2012, 5, 31), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 5, 31), callContext);

        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 31), new LocalDate(2012, 6, 30), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 6, 30), callContext);

        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 30), new LocalDate(2012, 7, 31), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 7, 31), callContext);

        //
        // FINALLY CANCEL SUBSCRIPTION EOT
        //
        subscription = subscriptionDataFromSubscription(cancelEntitlementAndCheckForCompletion(subscription, clock.getUTCNow()));

        // MOVE AFTER CANCEL DATE AND EXPECT EVENT : NextEvent.CANCEL
        addDaysAndCheckForCompletion(31, NextEvent.CANCEL);
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 7, 31), callContext);

        log.info("TEST PASSED !");
    }

    @Test(groups = "slow")
    public void testBasePlanCompleteWithBillingDayAlignedWithTrial() throws Exception {

        final int billingDay = 2;
        final DateTime initialCreationDate = new DateTime(2012, 2, 1, 0, 3, 42, 0, testTimeZone);

        log.info("Beginning test with BCD of " + billingDay);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));

        // set clock to the initial start date
        clock.setTime(initialCreationDate);
        int invoiceItemCount = 1;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        final DefaultEntitlement baseEntitlementAndCheckForCompletion = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.INVOICE);
        SubscriptionData subscription = subscriptionDataFromSubscription(baseEntitlementAndCheckForCompletion.getSubscription());
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(initialCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        // No end date for the trial item (fixed price of zero), and CTD should be today (i.e. when the trial started)
        invoiceChecker.checkChargedThroughDate(subscription.getId(), clock.getUTCToday(), callContext);

        //
        // CHANGE PLAN IMMEDIATELY AND EXPECT BOTH EVENTS: NextEvent.CHANGE NextEvent.INVOICE
        //
        subscription = subscriptionDataFromSubscription(changeEntitlementAndCheckForCompletion(subscription, "Assault-Rifle", BillingPeriod.MONTHLY, NextEvent.CHANGE, NextEvent.INVOICE));
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(initialCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), clock.getUTCToday(), callContext);

        //
        // MOVE 4 * TIME THE CLOCK
        //
        setDateAndCheckForCompletion(new DateTime(2012, 2, 28, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 2, 29, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 3, 1, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 3, 2, 23, 59, 59, 0, testTimeZone), NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 3, 2),
                                                                                                                   new LocalDate(2012, 4, 2), InvoiceItemType.RECURRING, new BigDecimal("599.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 4, 2), callContext);

        //
        // CHANGE PLAN EOT AND EXPECT NOTHING
        //
        subscription = subscriptionDataFromSubscription(changeEntitlementAndCheckForCompletion(subscription, "Pistol", BillingPeriod.MONTHLY));

        //
        // MOVE TIME AFTER CTD AND EXPECT BOTH EVENTS : NextEvent.CHANGE NextEvent.INVOICE
        //
        final LocalDate firstRecurringPistolDate = subscription.getChargedThroughDate().toLocalDate();
        final LocalDate secondRecurringPistolDate = firstRecurringPistolDate.plusMonths(1);
        addDaysAndCheckForCompletion(31, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 2), new LocalDate(2012, 5, 2), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), secondRecurringPistolDate, callContext);

        //
        // MOVE 3 * TIME AFTER NEXT BILL CYCLE DAY AND EXPECT EVENT : NextEvent.INVOICE, NextEvent.PAYMENT
        //
        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 2), new LocalDate(2012, 6, 2), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 6, 2), callContext);

        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 2), new LocalDate(2012, 7, 2), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 7, 2), callContext);

        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 2), new LocalDate(2012, 8, 2), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 8, 2), callContext);

        //
        // FINALLY CANCEL SUBSCRIPTION EOT
        //
        subscription = subscriptionDataFromSubscription(cancelEntitlementAndCheckForCompletion(subscription, clock.getUTCNow()));

        // MOVE AFTER CANCEL DATE AND EXPECT EVENT : NextEvent.CANCEL
        addDaysAndCheckForCompletion(31, NextEvent.CANCEL);
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 8, 2), callContext);

        log.info("TEST PASSED !");
    }

    @Test(groups = "slow")
    public void testBasePlanCompleteWithBillingDayInFuture() throws Exception {

        final int billingDay = 3;
        final DateTime initialCreationDate = new DateTime(2012, 2, 1, 0, 3, 42, 0, testTimeZone);

        log.info("Beginning test with BCD of " + billingDay);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));

        // set clock to the initial start date
        clock.setTime(initialCreationDate);
        int invoiceItemCount = 1;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        final DefaultEntitlement baseEntitlementAndCheckForCompletion = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.INVOICE);
        SubscriptionData subscription = subscriptionDataFromSubscription(baseEntitlementAndCheckForCompletion.getSubscription());


        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(initialCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        // No end date for the trial item (fixed price of zero), and CTD should be today (i.e. when the trial started)
        invoiceChecker.checkChargedThroughDate(subscription.getId(), clock.getUTCToday(), callContext);

        //
        // CHANGE PLAN IMMEDIATELY AND EXPECT BOTH EVENTS: NextEvent.CHANGE NextEvent.INVOICE
        //
        subscription = subscriptionDataFromSubscription(changeEntitlementAndCheckForCompletion(subscription, "Assault-Rifle", BillingPeriod.MONTHLY, NextEvent.CHANGE, NextEvent.INVOICE));
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(initialCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), clock.getUTCToday(), callContext);

        //
        // MOVE 4 * TIME THE CLOCK
        //
        setDateAndCheckForCompletion(new DateTime(2012, 2, 28, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 2, 29, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 3, 1, 23, 59, 59, 0, testTimeZone));
        setDateAndCheckForCompletion(new DateTime(2012, 3, 2, 23, 59, 59, 0, testTimeZone), NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT);
        // PRO_RATION
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 3, 2),
                                                                                                                   new LocalDate(2012, 3, 3), InvoiceItemType.RECURRING, new BigDecimal("20.70")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 3, 3), callContext);

        setDateAndCheckForCompletion(new DateTime(2012, 3, 3, 23, 59, 59, 0, testTimeZone), NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 3, 3),
                                                                                                                   new LocalDate(2012, 4, 3), InvoiceItemType.RECURRING, new BigDecimal("599.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 4, 3), callContext);

        //
        // CHANGE PLAN EOT AND EXPECT NOTHING
        //
        subscription = subscriptionDataFromSubscription(changeEntitlementAndCheckForCompletion(subscription, "Pistol", BillingPeriod.MONTHLY));

        //
        // MOVE TIME AFTER CTD AND EXPECT BOTH EVENTS : NextEvent.CHANGE NextEvent.INVOICE
        //
        final LocalDate firstRecurringPistolDate = subscription.getChargedThroughDate().toLocalDate();
        final LocalDate secondRecurringPistolDate = firstRecurringPistolDate.plusMonths(1);
        addDaysAndCheckForCompletion(31, NextEvent.CHANGE, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 4, 3), new LocalDate(2012, 5, 3), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), secondRecurringPistolDate, callContext);

        //
        // MOVE 3 * TIME AFTER NEXT BILL CYCLE DAY AND EXPECT EVENT : NextEvent.INVOICE, NextEvent.PAYMENT
        //
        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 5, 3), new LocalDate(2012, 6, 3), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 6, 3), callContext);

        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 6, 3), new LocalDate(2012, 7, 3), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 7, 3), callContext);

        addDaysAndCheckForCompletion(31, NextEvent.INVOICE, NextEvent.PAYMENT);
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(new LocalDate(2012, 7, 3), new LocalDate(2012, 8, 3), InvoiceItemType.RECURRING, new BigDecimal("29.95")));
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 8, 3), callContext);

        //
        // FINALLY CANCEL SUBSCRIPTION EOT
        //
        subscription = subscriptionDataFromSubscription(cancelEntitlementAndCheckForCompletion(subscription, clock.getUTCNow()));

        // MOVE AFTER CANCEL DATE AND EXPECT EVENT : NextEvent.CANCEL
        addDaysAndCheckForCompletion(31, NextEvent.CANCEL);
        invoiceChecker.checkChargedThroughDate(subscription.getId(), new LocalDate(2012, 8, 3), callContext);

        log.info("TEST PASSED !");


    }

    @Test(groups = {"stress"}, enabled = false)
    public void stressTest() throws Exception {
        final int maxIterations = 100;
        for (int curIteration = 0; curIteration < maxIterations; curIteration++) {
            if (curIteration != 0) {
                beforeMethod();
            }

            log.info("################################  ITERATION " + curIteration + "  #########################");
            afterMethod();
            beforeMethod();
            testBasePlanCompleteWithBillingDayInPast();
            Thread.sleep(1000);
            afterMethod();
            beforeMethod();
            testBasePlanCompleteWithBillingDayAlignedWithTrial();
            Thread.sleep(1000);
            afterMethod();
            beforeMethod();
            testBasePlanCompleteWithBillingDayInFuture();
            if (curIteration < maxIterations - 1) {
                afterMethod();
                Thread.sleep(1000);
            }
        }
    }

    @Test(groups = {"stress"}, enabled = false)
    public void stressTestDebug() throws Exception {
        final int maxIterations = 100;
        for (int curIteration = 0; curIteration < maxIterations; curIteration++) {
            log.info("################################  ITERATION " + curIteration + "  #########################");
            if (curIteration != 0) {
                beforeMethod();
            }
            testAddonsWithMultipleAlignments();
            if (curIteration < maxIterations - 1) {
                afterMethod();
                Thread.sleep(1000);
            }
        }
    }

    @Test(groups = "slow")
    public void testAddonsWithMultipleAlignments() throws Exception {
        final DateTime initialDate = new DateTime(2012, 4, 25, 0, 13, 42, 0, testTimeZone);
        clock.setDeltaFromReality(initialDate.getMillis() - clock.getUTCNow().getMillis());

        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(25));
        assertNotNull(account);

        final String productName = "Shotgun";
        final BillingPeriod term = BillingPeriod.MONTHLY;
        final String planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;


        final DefaultEntitlement baseEntitlementAndCheckForCompletion = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);


        // MOVE CLOCK A LITTLE BIT-- STILL IN TRIAL
        final Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(3));
        log.info("Moving clock from" + clock.getUTCNow() + " to " + clock.getUTCNow().plusDays(3));
        clock.addDays(3);

        busHandler.pushExpectedEvent(NextEvent.CREATE);
        busHandler.pushExpectedEvent(NextEvent.INVOICE);
        busHandler.pushExpectedEvent(NextEvent.PAYMENT);

        final DefaultEntitlement aoEntitlement1 = addAOEntitlementAndCheckForCompletion(baseEntitlementAndCheckForCompletion.getId(), "Telescopic-Scope", ProductCategory.ADD_ON, BillingPeriod.MONTHLY,
                                                                                        NextEvent.CREATE, NextEvent.INVOICE, NextEvent.PAYMENT);


        final DefaultEntitlement aoEntitlement2 = addAOEntitlementAndCheckForCompletion(baseEntitlementAndCheckForCompletion.getId(), "Laser-Scope", ProductCategory.ADD_ON, BillingPeriod.MONTHLY,
                                                                                        NextEvent.CREATE, NextEvent.INVOICE, NextEvent.PAYMENT);


        // MOVE CLOCK A LITTLE BIT MORE -- EITHER STAY IN TRIAL OR GET OUT
        busHandler.pushExpectedEvent(NextEvent.PHASE);
        busHandler.pushExpectedEvent(NextEvent.PHASE);
        busHandler.pushExpectedEvent(NextEvent.PAYMENT);
        busHandler.pushExpectedEvent(NextEvent.INVOICE);
        log.info("Moving clock from" + clock.getUTCNow() + " to " + clock.getUTCNow().plusDays(28));
        clock.addDays(28);// 26 / 5
        assertTrue(busHandler.isCompleted(DELAY));
        assertListenerStatus();

        busHandler.pushExpectedEvent(NextEvent.PHASE);
        busHandler.pushExpectedEvent(NextEvent.INVOICE);
        busHandler.pushExpectedEvent(NextEvent.PAYMENT);
        log.info("Moving clock from" + clock.getUTCNow() + " to " + clock.getUTCNow().plusDays(3));
        clock.addDays(3);// 29 / 5
        assertTrue(busHandler.isCompleted(DELAY));
        assertListenerStatus();

        log.info("Moving clock from" + clock.getUTCNow() + " to " + clock.getUTCNow().plusDays(10));
        clock.addDays(10);// 8 / 6
        assertTrue(busHandler.isCompleted(DELAY));
        assertListenerStatus();

        busHandler.pushExpectedEvent(NextEvent.INVOICE);
        busHandler.pushExpectedEvent(NextEvent.PAYMENT);
        log.info("Moving clock from" + clock.getUTCNow() + " to " + clock.getUTCNow().plusDays(18));
        clock.addDays(18);// 26 / 6
        assertTrue(busHandler.isCompleted(DELAY));
        assertListenerStatus();

        log.info("Moving clock from" + clock.getUTCNow() + " to " + clock.getUTCNow().plusDays(3));
        clock.addDays(3);
        assertTrue(busHandler.isCompleted(DELAY));
        assertListenerStatus();
    }


    @Test(groups = "slow")
    public void testWithRecreatePlan() throws Exception {
        final DateTime initialDate = new DateTime(2012, 2, 1, 0, 3, 42, 0, testTimeZone);
        final int billingDay = 2;

        log.info("Beginning test with BCD of " + billingDay);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));
        final UUID accountId = account.getId();
        assertNotNull(account);

        // set clock to the initial start date
        clock.setDeltaFromReality(initialDate.getMillis() - clock.getUTCNow().getMillis());

        String productName = "Shotgun";
        BillingPeriod term = BillingPeriod.MONTHLY;
        String planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        busHandler.pushExpectedEvent(NextEvent.CREATE);
        busHandler.pushExpectedEvent(NextEvent.INVOICE);

        final DefaultEntitlement baseEntitlementAndCheckForCompletion = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", productName, ProductCategory.BASE, term, NextEvent.CREATE, NextEvent.INVOICE);

        assertNotNull(baseEntitlementAndCheckForCompletion);
        assertTrue(busHandler.isCompleted(DELAY));

        //
        // VERIFY CTD HAS BEEN SET
        //
        final SubscriptionData subscription = (SubscriptionData) baseEntitlementAndCheckForCompletion.getSubscription();
        final DateTime startDate = subscription.getCurrentPhaseStart();
        final BigDecimal rate = subscription.getCurrentPhase().getFixedPrice().getPrice(Currency.USD);
        final int invoiceItemCount = 1;
        verifyTestResult(accountId, subscription.getId(), startDate, null, rate, clock.getUTCNow(), invoiceItemCount);

        //
        // MOVE TIME TO AFTER TRIAL AND EXPECT BOTH EVENTS :  NextEvent.PHASE NextEvent.INVOICE
        //
        busHandler.pushExpectedEvent(NextEvent.PHASE);
        busHandler.pushExpectedEvent(NextEvent.INVOICE);
        busHandler.pushExpectedEvent(NextEvent.PAYMENT);
        clock.addDeltaFromReality(AT_LEAST_ONE_MONTH_MS);
        assertTrue(busHandler.isCompleted(DELAY));

        final DefaultEntitlement entitlement = (DefaultEntitlement) entitlementApi.getEntitlementFromId(baseEntitlementAndCheckForCompletion.getId(), callContext);
        entitlement.cancelEntitlementWithDate(clock.getUTCNow().toLocalDate(), callContext);

        // MOVE AFTER CANCEL DATE AND EXPECT EVENT : NextEvent.CANCEL
        busHandler.pushExpectedEvent(NextEvent.CANCEL);
        DateTime endDate = subscription.getChargedThroughDate();
        final Interval it = new Interval(clock.getUTCNow(), endDate);
        clock.addDeltaFromReality(it.toDurationMillis());
        assertTrue(busHandler.isCompleted(DELAY));

        productName = "Assault-Rifle";
        term = BillingPeriod.MONTHLY;
        planSetName = PriceListSet.DEFAULT_PRICELIST_NAME;

        busHandler.pushExpectedEvent(NextEvent.RE_CREATE);
        busHandler.pushExpectedEvent(NextEvent.INVOICE);
        busHandler.pushExpectedEvent(NextEvent.PAYMENT);
        subscription.recreate(new PlanPhaseSpecifier(productName, ProductCategory.BASE, term, planSetName, null), endDate, callContext);
        assertTrue(busHandler.isCompleted(DELAY));

        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testForMultipleRecurringPhases() throws Exception {

        log.info("Starting testForMultipleRecurringPhases");

        final DateTime initialCreationDate = new DateTime(2012, 2, 1, 0, 3, 42, 0, testTimeZone);
        clock.setDeltaFromReality(initialCreationDate.getMillis() - clock.getUTCNow().getMillis());

        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(2));
        final UUID accountId = account.getId();

        final String productName = "Blowdart";
        final String planSetName = "DEFAULT";


        final DefaultEntitlement baseEntitlementAndCheckForCompletion = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", productName, ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.INVOICE);


        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(accountId, callContext);
        assertNotNull(invoices);
        assertTrue(invoices.size() == 1);

        busHandler.pushExpectedEvent(NextEvent.PHASE);
        busHandler.pushExpectedEvent(NextEvent.INVOICE);
        busHandler.pushExpectedEvent(NextEvent.PAYMENT);
        clock.addDeltaFromReality(AT_LEAST_ONE_MONTH_MS);
        assertTrue(busHandler.isCompleted(DELAY));
        invoices = invoiceUserApi.getInvoicesByAccount(accountId, callContext);
        assertNotNull(invoices);
        assertEquals(invoices.size(), 2);

        for (int i = 0; i < 5; i++) {
            log.info("============== loop number " + i + "=======================");
            busHandler.pushExpectedEvent(NextEvent.INVOICE);
            busHandler.pushExpectedEvent(NextEvent.PAYMENT);
            clock.addDeltaFromReality(AT_LEAST_ONE_MONTH_MS);
            assertTrue(busHandler.isCompleted(DELAY));
        }

        busHandler.pushExpectedEvent(NextEvent.INVOICE);
        busHandler.pushExpectedEvent(NextEvent.PAYMENT);
        busHandler.pushExpectedEvent(NextEvent.PHASE);
        clock.addDeltaFromReality(AT_LEAST_ONE_MONTH_MS);
        assertTrue(busHandler.isCompleted(DELAY));

        invoices = invoiceUserApi.getInvoicesByAccount(accountId, callContext);
        assertNotNull(invoices);
        assertEquals(invoices.size(), 8);

        for (int i = 0; i <= 5; i++) {
            log.info("============== second loop number " + i + "=======================");
            busHandler.pushExpectedEvent(NextEvent.INVOICE);
            busHandler.pushExpectedEvent(NextEvent.PAYMENT);
            clock.addDeltaFromReality(AT_LEAST_ONE_MONTH_MS);
            assertTrue(busHandler.isCompleted(DELAY));
        }

        invoices = invoiceUserApi.getInvoicesByAccount(accountId, callContext);
        assertNotNull(invoices);
        assertEquals(invoices.size(), 14);

        assertListenerStatus();
    }
}