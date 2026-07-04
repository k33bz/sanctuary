package com.k33bz.sanctuary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RespawnLedgerTest {

    // perDeath = 0.25 surcharge, decay 0.01 per 10 min (matching SurvivalLogicTest conventions).

    @Test
    void firstDeathPricesZeroAndBanksTheSurcharge() {
        // no prior escalation, no play time, no milestone change
        RespawnLedger.Update u = RespawnLedger.update(0.0, 0, 0.01, 0, 0, 0.25);
        assertEquals(0.0, u.priced, 1e-9);        // this death is priced at base
        assertEquals(0.25, u.nextStored, 1e-9);   // next death starts +0.25
    }

    @Test
    void repeatDeathAccumulatesOnTopOfDecayedValue() {
        // prior 0.25, shed 0.10 over 100 minutes -> priced 0.15, next stored 0.15 + 0.25
        RespawnLedger.Update u = RespawnLedger.update(0.25, 100, 0.01, 0, 0, 0.25);
        assertEquals(0.15, u.priced, 1e-9);
        assertEquals(0.40, u.nextStored, 1e-9);
    }

    @Test
    void newMilestoneResetsToleThenReapplyingPerDeath() {
        // prior 0.75, but a new milestone was crossed (2 > 1): priced 0, next stored is pure perDeath
        RespawnLedger.Update u = RespawnLedger.update(0.75, 0, 0.01, 2, 1, 0.25);
        assertEquals(0.0, u.priced, 1e-9);
        assertEquals(0.25, u.nextStored, 1e-9);
    }

    @Test
    void sameMilestoneCountDoesNotReset() {
        RespawnLedger.Update u = RespawnLedger.update(0.50, 0, 0.01, 3, 3, 0.25);
        assertEquals(0.50, u.priced, 1e-9);       // no reset
        assertEquals(0.75, u.nextStored, 1e-9);
    }

    @Test
    void patienceDecaysBelowZeroFloorsAtZero() {
        // huge play time sheds everything; priced floors at 0, next stored is just perDeath
        RespawnLedger.Update u = RespawnLedger.update(0.25, 1_000_000, 0.01, 0, 0, 0.25);
        assertEquals(0.0, u.priced, 1e-9);
        assertEquals(0.25, u.nextStored, 1e-9);
    }

    @Test
    void fullSequenceAccumulateThenDecayThenMilestoneReset() {
        // death 1: base -> stored 0.25
        RespawnLedger.Update d1 = RespawnLedger.update(0.0, 0, 0.01, 0, 0, 0.25);
        assertEquals(0.25, d1.nextStored, 1e-9);
        // death 2 immediately (no play, no milestone): priced 0.25, stored 0.50
        RespawnLedger.Update d2 = RespawnLedger.update(d1.nextStored, 0, 0.01, 0, 0, 0.25);
        assertEquals(0.25, d2.priced, 1e-9);
        assertEquals(0.50, d2.nextStored, 1e-9);
        // death 3 after 200 min played: sheds 0.20 -> priced 0.30, stored 0.55
        RespawnLedger.Update d3 = RespawnLedger.update(d2.nextStored, 200, 0.01, 0, 0, 0.25);
        assertEquals(0.30, d3.priced, 1e-9);
        assertEquals(0.55, d3.nextStored, 1e-9);
        // death 4 crosses a milestone: priced 0 regardless of the 0.55 backlog, stored 0.25
        RespawnLedger.Update d4 = RespawnLedger.update(d3.nextStored, 0, 0.01, 1, 0, 0.25);
        assertEquals(0.0, d4.priced, 1e-9);
        assertEquals(0.25, d4.nextStored, 1e-9);
    }
}
