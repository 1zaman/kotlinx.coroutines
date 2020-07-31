/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.rx2

import io.reactivex.*
import io.reactivex.disposables.*
import io.reactivex.exceptions.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Test
import kotlin.test.*

class ObservableExceptionHandlingTest : TestBase() {

    @Before
    fun setup() {
        ignoreLostThreads("RxComputationThreadPool-", "RxCachedWorkerPoolEvictor-", "RxSchedulerPurge-")
    }

    private inline fun <reified T : Throwable> handler(expect: Int) = { t: Throwable ->
        assertTrue(t is UndeliverableException && t.cause is T)
        expect(expect)
    }

    private fun cehUnreached() = CoroutineExceptionHandler { _, _ -> expectUnreached() }

    @Test
    fun testException() = withExceptionHandler({ expectUnreached() }) {
        rxObservable<Int>(Dispatchers.Unconfined + cehUnreached()) {
            expect(1)
            throw TestException()
        }.subscribe({
            expectUnreached()
        }, {
            expect(2) // Reported to onError
        })
        finish(3)
    }

    @Test
    fun testFatalException() = withExceptionHandler(handler<LinkageError>(3)) {
        rxObservable<Int>(Dispatchers.Unconfined) {
            expect(1)
            throw LinkageError()
        }.subscribe({
            expectUnreached()
        }, {
            expect(2)
        })
        finish(4)
    }

    @Test
    fun testExceptionAsynchronous() = withExceptionHandler({ expectUnreached() }) {
        rxObservable<Int>(Dispatchers.Unconfined) {
            expect(1)
            throw TestException()
        }.publish()
            .refCount()
            .subscribe({
                expectUnreached()
            }, {
                expect(2) // Reported to onError
            })
        finish(3)
    }

    @Test
    fun testFatalExceptionAsynchronous() = withExceptionHandler(handler<LinkageError>(3)) {
        rxObservable<Int>(Dispatchers.Unconfined) {
            expect(1)
            throw LinkageError()
        }.publish()
            .refCount()
            .subscribe({
                expectUnreached()
            }, {
                expect(2) // Fatal exception is not reported in onError
            })
        finish(4)
    }

    @Test
    fun testFatalExceptionFromSubscribe() = withExceptionHandler(handler<LinkageError>(4)) {
        rxObservable(Dispatchers.Unconfined) {
            expect(1)
            send(Unit)
        }.subscribe({
            expect(2)
            throw LinkageError()
        }, { expect(3) }) // Unreached because fatal errors are rethrown
        finish(5)
    }

    @Test
    fun testExceptionFromSubscribe() = withExceptionHandler({ expectUnreached() }) {
        rxObservable(Dispatchers.Unconfined) {
            expect(1)
            send(Unit)
        }.subscribe({
            expect(2)
            throw TestException()
        }, { expect(3) }) // not reported to onError because came from the subscribe itself
        finish(4)
    }

    @Test
    fun testAsynchronousExceptionFromSubscribe() = withExceptionHandler({ expectUnreached() }) {
        rxObservable(Dispatchers.Unconfined) {
            expect(1)
            send(Unit)
        }.publish()
            .refCount()
            .subscribe({
                expect(2)
                throw RuntimeException()
            }, { expect(3) })
        finish(4)
    }

    @Test
    fun testAsynchronousFatalExceptionFromSubscribe() = withExceptionHandler(handler<LinkageError>(4)) {
        rxObservable(Dispatchers.Unconfined) {
            expect(1)
            send(Unit)
        }.publish()
            .refCount()
            .subscribe({
                expect(2)
                throw LinkageError()
            }, { expect(3) })
        finish(5)
    }

    @Test
    fun testUnhandledException() = runTest {
        expect(1)
        var disposable: Disposable? = null
        val handler = { e: Throwable ->
            assertTrue(e is UndeliverableException && e.cause is TestException)
            expect(5)
        }
        val observable = rxObservable<Nothing>(currentDispatcher()) {
            expect(4)
            disposable!!.dispose() // cancel our own subscription, so that delay will get cancelled
            try {
                delay(Long.MAX_VALUE)
            } finally {
                throw TestException() // would not be able to handle it since mono is disposed
            }
        }
        withExceptionHandler(handler) {
            observable.subscribe(object : Observer<Nothing> {
                override fun onSubscribe(d: Disposable) {
                    expect(2)
                    disposable = d
                }

                override fun onNext(t: Nothing) {
                    expectUnreached()
                }

                override fun onError(t: Throwable) {
                    expectUnreached()
                }

                override fun onComplete() {
                    expectUnreached()
                }
            })
            expect(3)
            yield() // run coroutine
            finish(6)
        }
    }
}
