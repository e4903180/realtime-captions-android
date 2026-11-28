package tw.captions.core

/**
 * What happens to speaker letters after each finished line (ADR-039). One entry point for the
 * Android and iPhone apps.
 *
 * - 說話人數「自動」([speakers] <= 0): live grouping only. A letter, once shown, never changes. On
 *   real Taiwan-accent recordings regrouping with any fixed threshold split or merged people
 *   (docs/benchmark-speakers.md), while live grouping alone was steady.
 * - The user chose a number of people: all finished lines of this session are grouped again with
 *   at most that many people ([CaptionAssembler.recluster], older lines only renamed as a whole),
 *   and the live grouping takes over the result so the next line gets its final letter at once.
 */
object SpeakerPolicy {
    /** Average-linkage similarity two groups need to be one person when the number of people is given. */
    const val REGROUP_THRESHOLD = 0.55f

    /**
     * Call after every [TranscriptEvent.Final] (and when the number of people changes). [clusterer]
     * is the live grouping (null for engines with their own speaker labels, e.g. Azure).
     */
    fun afterFinal(assembler: CaptionAssembler, clusterer: SpeakerClusterer?, speakers: Int): CaptionState {
        if (speakers <= 0) return assembler.state
        val state = assembler.recluster(REGROUP_THRESHOLD, speakers)
        clusterer?.replaceProfiles(assembler.speakerProfiles())
        return state
    }
}
