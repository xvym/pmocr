package io.github.xvym.pmocr.translation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TranslationIndexTest {
    @Test
    public void exactLookupIgnoresLineBreakAndWhitespaceDifferences() {
        TranslationIndex index = new TranslationIndex();
        index.addEntry("ポケモン は\nすごい", "宝可梦很厉害");
        index.prepare();

        assertEquals("宝可梦很厉害", index.translate("ポケモンは すごい"));
    }

    @Test
    public void templateLookupReplacesCapturedNouns() {
        TranslationIndex index = new TranslationIndex();
        index.addNoun("ピカチュウ", "皮卡丘");
        index.addEntry("【POKEMON】は つよい！", "【POKEMON】很强！");
        index.prepare();

        assertEquals("皮卡丘很强！", index.translate("ピカチュウは つよい！"));
    }

    @Test
    public void repositoryFallsBackToLineByLineLookup() {
        TranslationIndex index = new TranslationIndex();
        index.addEntry("あい", "第一句");
        index.addEntry("うえ", "第二句");
        XlsxTranslationRepository repository = XlsxTranslationRepository.fromIndex(index, "test");

        assertEquals("第一句\n第二句", repository.translate(" あい \n うえ "));
    }

    @Test
    public void repositoryReturnsNotFoundForUnknownText() {
        TranslationIndex index = new TranslationIndex();
        XlsxTranslationRepository repository = XlsxTranslationRepository.fromIndex(index, "test");

        assertEquals("无文本", repository.translate("みつからない"));
    }
}
