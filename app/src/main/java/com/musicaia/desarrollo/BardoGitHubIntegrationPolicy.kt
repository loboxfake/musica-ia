package com.musicaia.desarrollo

/**
 * Guarda de seguridad para la integración candidata.
 *
 * El workflow antiguo bardo-commit-candidate.yml escribe directamente en main.
 * Hasta que exista y se audite un workflow que cree una rama y un PR borrador,
 * la aplicación debe permitir validar, pero no despachar la integración.
 */
object BardoGitHubIntegrationPolicy {
    const val DRAFT_PR_WORKFLOW_ENABLED = false

    const val BLOCK_REASON =
        "Creación de PR bloqueada: el workflow disponible bardo-commit-candidate.yml " +
        "hace push directo a main. BARDO no lo ejecutará. La integración solo se " +
        "habilitará cuando exista un workflow auditado que cree una rama bardo/* y " +
        "un PR borrador sin escribir en main."

    fun requireDraftPrWorkflowAvailable() {
        check(DRAFT_PR_WORKFLOW_ENABLED) { BLOCK_REASON }
    }
}
