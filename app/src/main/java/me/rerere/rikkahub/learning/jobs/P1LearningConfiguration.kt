package me.rerere.rikkahub.learning.jobs

/** Content-free control-flow signal; handlers translate it to WAITING_CONFIGURATION. */
class P1LearningConfigurationUnavailableException : IllegalStateException(
    "p1_learning_configuration_unavailable",
)
