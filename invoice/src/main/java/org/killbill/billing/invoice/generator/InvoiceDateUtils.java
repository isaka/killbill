/*
 * Copyright 2010-2014 Ning, Inc.
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

package org.killbill.billing.invoice.generator;

import java.math.BigDecimal;

import org.joda.time.Days;
import org.joda.time.LocalDate;
import org.joda.time.Months;
import org.joda.time.Weeks;
import org.joda.time.Years;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.util.currency.KillBillMoney;
import org.killbill.commons.utils.annotation.VisibleForTesting;

public class InvoiceDateUtils {

    public static int calculateNumberOfWholeBillingPeriods(final LocalDate startDate, final LocalDate endDate, final BillingPeriod billingPeriod) {
        final int numberBetween;
        final int numberInPeriod;
        if (billingPeriod.getPeriod().getDays() != 0) {
            numberBetween = Days.daysBetween(startDate, endDate).getDays();
            numberInPeriod = billingPeriod.getPeriod().getDays();
        } else if (billingPeriod.getPeriod().getWeeks() != 0) {
            numberBetween = Weeks.weeksBetween(startDate, endDate).getWeeks();
            numberInPeriod = billingPeriod.getPeriod().getWeeks();
        } else if (billingPeriod.getPeriod().getMonths() != 0) {
            numberBetween = Months.monthsBetween(startDate, endDate).getMonths();
            numberInPeriod = billingPeriod.getPeriod().getMonths();
        } else {
            numberBetween = Years.yearsBetween(startDate, endDate).getYears();
            numberInPeriod = billingPeriod.getPeriod().getYears();
        }
        return numberBetween / numberInPeriod;
    }

    public static BigDecimal calculateProRationBeforeFirstBillingPeriod(final LocalDate startDate, final LocalDate nextBillingCycleDate,
                                                                        final BillingPeriod billingPeriod, final int prorationFixedDays) {
        final LocalDate previousBillingCycleDate = nextBillingCycleDate.minus(billingPeriod.getPeriod());
        return calculateProrationBetweenDates(startDate, nextBillingCycleDate, previousBillingCycleDate, nextBillingCycleDate, prorationFixedDays);
    }

    public static BigDecimal calculateProRationAfterLastBillingCycleDate(final LocalDate endDate, final LocalDate previousBillThroughDate,
                                                                         final BillingPeriod billingPeriod, final int prorationFixedDays) {
        // Note: assumption is that previousBillThroughDate is correctly aligned with the billing cycle day
        final LocalDate nextBillThroughDate = previousBillThroughDate.plus(billingPeriod.getPeriod());
        return calculateProrationBetweenDates(previousBillThroughDate, endDate, previousBillThroughDate, nextBillThroughDate, prorationFixedDays);
    }

    /**
     * Called internally to calculate proration or when we recalculate approximate repair amount
     *
     * @param startDate                start date of the prorated interval
     * @param endDate                  end date of the prorated interval
     * @param previousBillingCycleDate start date of the period
     * @param nextBillingCycleDate     end date of the period
     * @param fixedDaysInMonth         fixed days to consider in a month (to avoid proration)
     */
    private static BigDecimal calculateProrationBetweenDates(final LocalDate startDate, final LocalDate endDate, final LocalDate previousBillingCycleDate, final LocalDate nextBillingCycleDate, final int fixedDaysInMonth) {
        final int daysBetween = fixedDaysInMonth == 0 ? Days.daysBetween(previousBillingCycleDate, nextBillingCycleDate).getDays() : fixedDaysInMonth;
        return calculateProrationBetweenDates(startDate, endDate, daysBetween, fixedDaysInMonth);
    }

    public static BigDecimal calculateProrationBetweenDates(final LocalDate startDate, final LocalDate endDate, final int daysBetween, final int fixedDaysInMonth) {
        if (daysBetween <= 0) {
            return BigDecimal.ZERO;
        }

        final BigDecimal daysInPeriod = new BigDecimal(daysBetween);
        final BigDecimal days = fixedDaysInMonth == 0 ? new BigDecimal(Days.daysBetween(startDate, endDate).getDays()) : new BigDecimal(daysBetweenWithFixedDaysInMonth(startDate, endDate, fixedDaysInMonth));

        return days.divide(daysInPeriod, KillBillMoney.MAX_SCALE, KillBillMoney.ROUNDING_METHOD);
    }

    @VisibleForTesting
    public static int daysBetweenWithFixedDaysInMonth(final LocalDate startDate, final LocalDate endDate, final int fixedDaysInMonth) {
        final int daysBetween = Days.daysBetween(startDate, endDate).getDays();
        if(startDate.getMonthOfYear() == endDate.getMonthOfYear()) { //same month, no need for extra logic
            return daysBetween;
        }
        final int lastDayOfMonth = startDate.dayOfMonth().withMaximumValue().getDayOfMonth();
        return daysBetween - (lastDayOfMonth - fixedDaysInMonth);
    }

    public static LocalDate advanceByNPeriods(final LocalDate initialDate, final BillingPeriod billingPeriod, final int nbPeriods) {
        LocalDate proposedDate = initialDate;
        for (int i = 0; i < nbPeriods; i++) {
            proposedDate = proposedDate.plus(billingPeriod.getPeriod());
        }
        return proposedDate;
    }

    public static LocalDate recedeByNPeriods(final LocalDate initialDate, final BillingPeriod billingPeriod, final int nbPeriods) {
        LocalDate proposedDate = initialDate;
        for (int i = 0; i < nbPeriods; i++) {
            proposedDate = proposedDate.minus(billingPeriod.getPeriod());
        }
        return proposedDate;
    }
}
