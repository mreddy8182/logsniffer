package com.logsniffer.source.compound;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logsniffer.model.Log;
import com.logsniffer.model.LogInputStream;
import com.logsniffer.model.LogPointer;
import com.logsniffer.model.LogRawAccess;
import com.logsniffer.model.Navigation;
import com.logsniffer.model.Navigation.DateOffsetNavigation;
import com.logsniffer.model.support.ByteLogAccess;
import com.logsniffer.model.support.TimestampNavigation;
import com.logsniffer.reader.LogEntryReader;
import com.logsniffer.source.compound.CompoundLogPointer.LogInstanceResolver;
import com.logsniffer.source.compound.CompoundLogPointer.PointerPart;

/**
 * Access to composed logs.
 * 
 * @author mbok
 *
 */
public class CompoundLogAccess
		implements LogRawAccess<LogInputStream>, LogInputStream, DateOffsetNavigation, LogInstanceResolver {
	private static final Logger logger = LoggerFactory.getLogger(CompoundLogAccess.class);
	private final PointerPartBuilder POINTER_BUILDER_START = new PointerPartBuilder() {
		@Override
		public LogPointer build(final LogInstance logInstance) throws IOException {
			return logInstance.getLogAccess().start();
		}
	};
	private final PointerPartBuilder POINTER_BUILDER_END = new PointerPartBuilder() {
		@Override
		public LogPointer build(final LogInstance logInstance) throws IOException {
			return logInstance.getLogAccess().end();
		}
	};

	private final List<LogInstance> composedLogs;
	private final Log thisLog;

	private static interface PointerPartBuilder {
		LogPointer build(LogInstance logInstance) throws IOException;
	}

	public CompoundLogAccess(final Log thisLog, final List<LogInstance> composedLogs) {
		super();
		this.thisLog = thisLog;
		this.composedLogs = composedLogs;
	}

	@Override
	public long getDifference(final LogPointer source, final LogPointer compareTo) throws IOException {
		return getPositionFromStart(compareTo) - getPositionFromStart(source);
	}

	private long getPositionFromStart(final LogPointer pointer) throws IOException {
		if (pointer == null) {
			return 0;
		} else if (!(pointer instanceof CompoundLogPointer)) {
			throw new IOException("Pointer has a wrong type: " + pointer);
		}
		long totalPos = 0;
		final CompoundLogPointer cp = (CompoundLogPointer) pointer;
		for (final Pair<LogInstance, LogPointer> p : mapPointer(cp)) {
			if (p.getRight() != null) {
				totalPos += p.getLeft().getLogAccess().getDifference(null, p.getRight());
			}
		}
		return totalPos;
	}

	@SuppressWarnings("unchecked")
	protected Pair<LogInstance, LogPointer>[] mapPointer(final CompoundLogPointer cp) {
		final Pair<LogInstance, LogPointer>[] mapped = new Pair[composedLogs.size()];
		final PointerPart[] parts = Arrays.copyOf(cp.getParts(), cp.getParts().length);
		int i = 0;
		for (final LogInstance sl : composedLogs) {
			LogPointer partPointer = null;
			int z = -1;
			for (final PointerPart pp : parts) {
				z++;
				if (pp == null) {
					// Remove pointer to avoid reuse in case of the same log is
					// referenced multiple times
					continue;
				}
				if (pp.getLogSourceId() == sl.getLogSourceId()
						&& pp.getLogPathHash() == sl.getLog().getPath().hashCode()) {
					partPointer = pp.getOffset();
					parts[z] = null;
					break;
				}
			}
			if (partPointer == null) {
				logger.warn("No pointer information found for composed log {} in part {} for pointer: {}", thisLog, sl,
						cp);
			}
			mapped[i++] = Pair.of(sl, partPointer);
		}
		return mapped;
	}

	@Override
	public LogPointer getFromJSON(final String data) throws IOException {
		return CompoundLogPointer.fromJson(data, this);
	}

	@Override
	public LogInputStream getInputStream(final LogPointer from) throws IOException {
		return this;
	}

	private PointerPart[] buildPointerParts(final PointerPartBuilder b) throws IOException {
		final PointerPart[] pointerParts = new PointerPart[composedLogs.size()];
		int i = 0;
		for (final LogInstance sl : composedLogs) {
			final LogPointer p = b.build(sl);
			pointerParts[i++] = new PointerPart(sl.getLogSourceId(), sl.getLog().getPath(), p);
		}
		return pointerParts;
	}

	@Override
	public LogPointer end() throws IOException {
		return new CompoundLogPointer(buildPointerParts(POINTER_BUILDER_END), new Date(Long.MAX_VALUE));
	}

	@Override
	public LogPointer start() throws IOException {
		return new CompoundLogPointer(buildPointerParts(POINTER_BUILDER_START), new Date(0));
	}

	@Override
	public NavigationFuture refresh(final LogPointer toRefresh) throws IOException {
		if (toRefresh == null) {
			return new NavigationFuture() {
				@Override
				public LogPointer get() throws IOException {
					return null;
				}
			};
		} else if (!(toRefresh instanceof CompoundLogPointer)) {
			throw new IOException("Pointer has a wrong type: " + toRefresh);
		} else if (((CompoundLogPointer) toRefresh).getCurrentTimestamp() != null
				&& ((CompoundLogPointer) toRefresh).getCurrentTimestamp().getTime() == 0) {
			return new NavigationFuture() {
				@Override
				public LogPointer get() throws IOException {
					return start();
				}
			};
		}
		return new NavigationFuture() {
			@Override
			public LogPointer get() throws IOException {
				final long start = System.currentTimeMillis();
				final CompoundLogPointer cp = (CompoundLogPointer) toRefresh;
				final PointerPart[] refreshedParts = new PointerPart[composedLogs.size()];
				int i = 0;
				for (final Pair<LogInstance, LogPointer> p : mapPointer(cp)) {
					LogPointer refreshedPointer = null;
					final LogInstance logInstance = p.getLeft();
					if (p.getRight() != null) {
						refreshedPointer = logInstance.getLogAccess().refresh(p.getRight()).get();
					} else {
						refreshedPointer = navigateTo(logInstance, cp.getCurrentTimestamp()).get();
					}
					refreshedParts[i++] = new PointerPart(logInstance.getLogSourceId(), logInstance.getLog().getPath(),
							refreshedPointer);
				}
				final CompoundLogPointer cpr = new CompoundLogPointer(refreshedParts, cp.getCurrentTimestamp());
				logger.debug("Refreshed pointer in {}ms {} => {}", System.currentTimeMillis() - start, cp, cpr);
				return cpr;
			}
		};
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected NavigationFuture navigateTo(final LogInstance logInstance, final Date currentTimestamp)
			throws IOException {
		final LogRawAccess<?> logInstanceRawAccess = logInstance.getLogAccess();
		if (logInstanceRawAccess instanceof ByteLogAccess) {
			return new TimestampNavigation(logInstance.getLog(), (ByteLogAccess) logInstanceRawAccess,
					(LogEntryReader) logInstance.getReader()).absolute(currentTimestamp);
		} else {
			final Navigation<?> navigation = logInstanceRawAccess.getNavigation();
			if (navigation instanceof DateOffsetNavigation) {
				return ((DateOffsetNavigation) navigation).absolute(currentTimestamp);
			}
		}
		throw new IOException("Can't navigate by date in log instance: " + logInstance);
	}

	@Override
	public LogPointer getPointer() throws IOException {
		return null;
	}

	@Override
	public Navigation<?> getNavigation() {
		return this;
	}

	@Override
	public NavigationFuture absolute(final Date offset) throws IOException {
		logger.debug("Navigating to date offset: {}", offset);
		return refresh(new CompoundLogPointer(new PointerPart[0], offset));
	}

	@Override
	public LogInstance resolveForPathHash(final long sourceId, final int pathHash) {
		for (final LogInstance sl : composedLogs) {
			if (sl.getLogSourceId() == sourceId && sl.getLog().getPath().hashCode() == pathHash) {
				return sl;
			}
		}
		return null;
	}

}
