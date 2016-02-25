package test;

import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.ProjectStats;
import edu.umd.cs.findbugs.log.Profiler;

import java.io.IOException;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;

public class ABCTest {

    @Mock private BugReporter bugReporter;
    @Mock private Profiler profiler;
    @Mock private ProjectStats projectStats;

    @BeforeMethod
    public void setUp() {
        FindBugs.setHome(".");
        MockitoAnnotations.initMocks(this);

        when(bugReporter.getProjectStats()).thenReturn(projectStats);
        when(projectStats.getProfiler()).thenReturn(profiler);
    }

    @Test
    public void shouldFireOnSample() throws InterruptedException, IOException {
        FindBugs2 engine = new FindBugs2();
        engine.setDetectorFactoryCollection(DetectorFactoryCollection.instance());
        engine.setBugReporter(bugReporter);
        engine.execute();
    }

}
