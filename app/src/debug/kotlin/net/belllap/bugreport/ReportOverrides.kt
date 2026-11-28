package net.belllap.bugreport

/** Debug builds only: lets an instrumented test point bug reports at a fake backend. Release has no setters. */
object ReportOverrides {
    @Volatile
    var api: ReportApi? = null

    @Volatile
    var tokens: AppCheckTokenSource? = null
}
